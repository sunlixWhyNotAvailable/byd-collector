package main

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/envknob"
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

type request struct {
	ID        string `json:"id"`
	Directory string `json:"directory"`
	Name      string `json:"name"`
	Key       string `json:"key"`
	Host      string `json:"host"`
	Port      int    `json:"port"`
	Token     string `json:"token"`
	RequestID string `json:"requestId"`
	Profile   string `json:"profile"`
	Purpose   string `json:"purpose"`
}

type result struct {
	ID        string `json:"id,omitempty"`
	State     string `json:"state"`
	LoginURL  string `json:"loginUrl,omitempty"`
	Port      int    `json:"port,omitempty"`
	Token     string `json:"token,omitempty"`
	Error     string `json:"error,omitempty"`
	Code      string `json:"code,omitempty"`
	RequestID string `json:"requestId,omitempty"`
	Profile   string `json:"profile,omitempty"`
}

type bridgeCapability struct {
	target    string
	requestID string
	profile   string
	influx    bool
	ctx       context.Context
	cancel    context.CancelFunc
}

type runtimeState struct {
	id           string
	server       *tsnet.Server
	ctx          context.Context
	cancel       context.CancelFunc
	mu           sync.Mutex
	snapshot     result
	proxy        *http.Server
	port         int
	transport    *http.Transport
	capabilities map[string]*bridgeCapability
	connections  map[net.Conn]struct{}
}

var operationMu sync.Mutex
var current *runtimeState
var deliverEvent = func(string) {}
var urlPattern = regexp.MustCompile(`(?i)\b(?:https?|wss?)://[^\s<>"']+`)
var credentialPattern = regexp.MustCompile(`\btskey-[A-Za-z0-9_-]+\b`)
var requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,80}$`)

func main() {}
func init() { log.SetOutput(io.Discard) }

func safeError(err error) string {
	if err == nil {
		return ""
	}
	return credentialPattern.ReplaceAllString(urlPattern.ReplaceAllString(err.Error(), "[url redacted]"), "[key redacted]")
}

func marshalResult(v result) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"state":"error","error":"encode result failed"}`
	}
	return string(b)
}

func command(operation, raw string) (output string) {
	defer func() {
		if failure := recover(); failure != nil {
			output = marshalResult(result{State: "error", Error: safeError(fmt.Errorf("native panic during %s: %v\n%s", operation, failure, debug.Stack()))})
		}
	}()
	var input request
	if len(raw) > 32768 {
		return marshalResult(result{State: "error", Error: "native request too large"})
	}
	if err := json.Unmarshal([]byte(raw), &input); err != nil {
		return marshalResult(result{State: "error", Error: "invalid native request: " + safeError(err)})
	}
	operationMu.Lock()
	defer operationMu.Unlock()
	failed := func(err error) string {
		return marshalResult(result{ID: input.ID, State: "error", Error: safeError(fmt.Errorf("%s: %w", operation, err))})
	}
	if operation == "start" {
		if current != nil {
			if current.id != input.ID {
				return failed(errors.New("another native owner is active"))
			}
			return marshalResult(current.status())
		}
		key, err := base64.StdEncoding.DecodeString(input.Key)
		if err != nil {
			return failed(errors.New("invalid state key encoding"))
		}
		defer clear(key)
		if !filepath.IsAbs(input.Directory) || !strings.HasPrefix(input.Name, "byd-collector-") || input.ID == "" {
			return failed(errors.New("invalid native identity"))
		}
		store, err := newEncryptedStore(filepath.Join(input.Directory, "state"), key)
		if err != nil {
			return failed(err)
		}
		// Android has no HOME or writable /tmp; even disabled loggers resolve this directory.
		logs := filepath.Join(input.Directory, "logs")
		if err := os.MkdirAll(logs, 0700); err != nil {
			return failed(fmt.Errorf("native private log directory: %w", err))
		}
		if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
			return failed(err)
		}
		envknob.SetNoLogsNoSupport()
		ctx, cancel := context.WithCancel(context.Background())
		r := &runtimeState{id: input.ID, ctx: ctx, cancel: cancel, capabilities: map[string]*bridgeCapability{}, connections: map[net.Conn]struct{}{}, snapshot: result{ID: input.ID, State: "starting"}}
		r.server = &tsnet.Server{Dir: input.Directory, Hostname: input.Name, Store: store, Logf: func(string, ...any) {}, UserLogf: func(string, ...any) {}}
		if err := r.server.Start(); err != nil {
			cancel()
			return failed(errors.Join(err, r.server.Close()))
		}
		r.transport = &http.Transport{DialContext: r.server.Dial, MaxIdleConns: 4, IdleConnTimeout: 30 * time.Second, ResponseHeaderTimeout: 10 * time.Second, DisableCompression: true}
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			cancel()
			return failed(errors.Join(err, r.server.Close()))
		}
		r.port = listener.Addr().(*net.TCPAddr).Port
		r.proxy = &http.Server{Handler: http.HandlerFunc(r.proxyRequest), ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 * 1024}
		current = r
		go func() {
			if err := r.proxy.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
				r.publish(result{State: "error", Error: safeError(fmt.Errorf("local bridge: %w", err))})
			}
		}()
		go r.watch()
		return marshalResult(r.status())
	}
	if current == nil {
		return marshalResult(result{ID: input.ID, State: "stopped"})
	}
	if input.ID != current.id {
		return failed(errors.New("native owner generation mismatch"))
	}
	r := current
	switch operation {
	case "status":
		return marshalResult(r.status())
	case "network":
		r.server.NetworkChanged()
		return marshalResult(r.status())
	case "lease":
		if input.Host == "" || strings.ContainsAny(input.Host, "\r\n\x00/\\ \t") || input.Port < 1 || input.Port > 65535 {
			return failed(errors.New("invalid destination"))
		}
		if input.Purpose != "mqtt" && input.Purpose != "influx" {
			return failed(errors.New("invalid bridge purpose"))
		}
		if input.Purpose == "influx" && (!requestIDPattern.MatchString(input.RequestID) ||
			(input.Profile != "primary" && input.Profile != "alternative" && input.Profile != "unknown")) {
			return failed(errors.New("invalid bridge correlation"))
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.snapshot.State != "ready" {
			if r.snapshot.State == "starting" {
				return marshalResult(result{ID: r.id, State: "error", Code: "tailnet_starting", Error: "lease: tailnet is starting"})
			}
			return failed(fmt.Errorf("tailnet is %s", r.snapshot.State))
		}
		tokenBytes := make([]byte, 32)
		if _, err := rand.Read(tokenBytes); err != nil {
			return failed(err)
		}
		token := base64.RawURLEncoding.EncodeToString(tokenBytes)
		ctx, cancel := context.WithCancel(r.ctx)
		r.capabilities[token] = &bridgeCapability{target: net.JoinHostPort(strings.Trim(input.Host, "[]"), strconv.Itoa(input.Port)),
			requestID: input.RequestID, profile: input.Profile, influx: input.Purpose == "influx", ctx: ctx, cancel: cancel}
		return marshalResult(result{ID: r.id, State: "ready", Port: r.port, Token: token})
	case "release":
		r.releaseCapability(input.Token)
		return marshalResult(r.status())
	case "close":
		current = nil
		r.cancel()
		err := r.proxy.Close()
		r.mu.Lock()
		connections := make([]net.Conn, 0, len(r.connections))
		for conn := range r.connections {
			connections = append(connections, conn)
		}
		r.capabilities = map[string]*bridgeCapability{}
		r.mu.Unlock()
		for _, conn := range connections {
			err = errors.Join(err, conn.Close())
		}
		r.transport.CloseIdleConnections()
		err = errors.Join(err, r.server.Close())
		if err != nil {
			return failed(err)
		}
		return marshalResult(result{ID: r.id, State: "stopped"})
	default:
		return failed(errors.New("unknown native operation"))
	}
}

func (r *runtimeState) releaseCapability(token string) {
	r.mu.Lock()
	capability := r.capabilities[token]
	delete(r.capabilities, token)
	r.mu.Unlock()
	if capability != nil {
		capability.cancel()
	}
}

func (r *runtimeState) status() result { r.mu.Lock(); defer r.mu.Unlock(); return r.snapshot }
func (r *runtimeState) publish(next result) {
	if r.ctx.Err() != nil {
		return
	}
	next.ID = r.id
	r.mu.Lock()
	changed := next != r.snapshot
	r.snapshot = next
	r.mu.Unlock()
	if changed {
		deliverEvent(marshalResult(next))
	}
}

func (r *runtimeState) watch() {
	client, err := r.server.LocalClient()
	if err != nil {
		r.publish(result{State: "error", Error: safeError(fmt.Errorf("local client: %w", err))})
		return
	}
	for r.ctx.Err() == nil {
		watcher, err := client.WatchIPNBus(r.ctx, ipn.NotifyInitialState)
		if err == nil {
			for {
				notification, readErr := watcher.Next()
				if readErr != nil {
					err = readErr
					break
				}
				if notification.State == nil && notification.BrowseToURL == nil && notification.ErrMessage == nil {
					continue
				}
				ctx, cancel := context.WithTimeout(r.ctx, 2*time.Second)
				status, statusErr := client.Status(ctx)
				cancel()
				if statusErr != nil {
					r.publish(result{State: "error", Error: safeError(fmt.Errorf("tailnet status: %w", statusErr))})
					continue
				}
				state := "starting"
				if status == nil {
					continue
				}
				switch status.BackendState {
				case ipn.Running.String():
					state = "ready"
				case ipn.NeedsLogin.String():
					state = "login_required"
				case ipn.NeedsMachineAuth.String():
					state = "approval_pending"
				case ipn.Stopped.String():
					state = "stopped"
				}
				next := result{State: state, LoginURL: status.AuthURL}
				if notification.ErrMessage != nil {
					next.State = "error"
					next.Error = safeError(errors.New(*notification.ErrMessage))
				}
				r.publish(next)
			}
			watcher.Close()
		}
		if r.ctx.Err() != nil {
			return
		}
		r.publish(result{State: "error", Error: safeError(fmt.Errorf("tailnet notifications: %w", err))})
		select {
		case <-r.ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}
}
