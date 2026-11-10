package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

func (r *runtimeState) proxyRequest(w http.ResponseWriter, req *http.Request) {
	// A capability names exactly one destination and dies with its Java operation.
	token := strings.TrimPrefix(req.Header.Get("Proxy-Authorization"), "Bearer ")
	r.mu.Lock()
	capability, ok := r.capabilities[token]
	r.mu.Unlock()
	if !ok {
		http.Error(w, "bridge authorization required", http.StatusProxyAuthRequired)
		return
	}
	target := capability.target
	ctx, cancel := capability.requestContext(req.Context())
	defer cancel()
	if err := ctx.Err(); err != nil {
		r.bridgeError(w, capability, "bridge request cancelled", err)
		return
	}
	requested := req.URL.Host
	if req.Method != http.MethodConnect && req.URL.Port() == "" {
		requested = net.JoinHostPort(req.URL.Hostname(), "80")
	}
	if req.Method == http.MethodConnect {
		requested = req.Host
	}
	if req.URL.User != nil || !strings.EqualFold(requested, target) {
		http.Error(w, "bridge destination mismatch", http.StatusForbidden)
		return
	}
	if req.Method == http.MethodConnect {
		r.connect(w, req, capability, ctx)
		return
	}
	if req.URL.Scheme != "http" {
		http.Error(w, "unsupported bridge scheme", http.StatusBadRequest)
		return
	}
	out := req.Clone(ctx)
	out.RequestURI = ""
	out.Header = req.Header.Clone()
	stripHopHeaders(out.Header)
	out.Header.Del("Proxy-Authorization")
	response, err := r.transport.RoundTrip(out)
	if err != nil {
		r.bridgeError(w, capability, "tailnet HTTP", err)
		return
	}
	defer response.Body.Close()
	stripHopHeaders(response.Header)
	response.Header.Del("X-Collector-Bridge-Error")
	for key, values := range response.Header {
		for _, value := range values {
			w.Header().Add(key, value)
		}
	}
	w.WriteHeader(response.StatusCode)
	if _, err := io.Copy(w, response.Body); err != nil {
		r.transportError(capability, "HTTP response copy", errors.Join(err, ctx.Err()))
		// Do not retry a partially delivered response or turn it into a second write.
		panic(http.ErrAbortHandler)
	}
}

func (capability *bridgeCapability) requestContext(parent context.Context) (context.Context, context.CancelFunc) {
	var ctx context.Context
	var cancel context.CancelFunc
	if capability.influx {
		ctx, cancel = context.WithTimeout(parent, 15*time.Second)
	} else {
		ctx, cancel = context.WithCancel(parent)
	}
	stop := context.AfterFunc(capability.ctx, cancel)
	if capability.ctx.Err() != nil {
		cancel()
	}
	return ctx, func() { stop(); cancel() }
}

func stripHopHeaders(header http.Header) {
	for _, value := range header.Values("Connection") {
		for _, name := range strings.Split(value, ",") {
			header.Del(strings.TrimSpace(name))
		}
	}
	for _, name := range []string{"Connection", "Proxy-Connection", "Proxy-Authenticate", "Proxy-Authorization", "Keep-Alive", "TE", "Trailer", "Transfer-Encoding", "Upgrade"} {
		header.Del(name)
	}
}

func (r *runtimeState) connect(w http.ResponseWriter, req *http.Request, capability *bridgeCapability, ctx context.Context) {
	dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	remote, err := r.server.Dial(dialCtx, "tcp", capability.target)
	cancel()
	if err != nil {
		r.bridgeError(w, capability, "tailnet TCP", err)
		return
	}
	defer remote.Close()
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "bridge cannot tunnel", http.StatusInternalServerError)
		return
	}
	local, buffer, err := hijacker.Hijack()
	if err != nil {
		r.transportError(capability, "CONNECT hijack", err)
		return
	}
	defer local.Close()
	stopCancellation := context.AfterFunc(ctx, func() { local.Close(); remote.Close() })
	defer stopCancellation()
	r.mu.Lock()
	if r.ctx.Err() != nil {
		r.mu.Unlock()
		return
	}
	r.connections[local] = struct{}{}
	r.connections[remote] = struct{}{}
	r.mu.Unlock()
	defer func() { r.mu.Lock(); delete(r.connections, local); delete(r.connections, remote); r.mu.Unlock() }()
	if _, err = buffer.WriteString("HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		r.transportError(capability, "CONNECT response", errors.Join(err, ctx.Err()))
		return
	}
	if err = buffer.Flush(); err != nil {
		r.transportError(capability, "CONNECT flush", errors.Join(err, ctx.Err()))
		return
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, err := io.Copy(remote, buffer)
		r.transportError(capability, "TCP uplink", errors.Join(err, ctx.Err()))
		remote.Close()
	}()
	_, err = io.Copy(local, remote)
	r.transportError(capability, "TCP downlink", errors.Join(err, ctx.Err()))
	local.Close()
	remote.Close()
	<-done
}

func (r *runtimeState) transportError(capability *bridgeCapability, stage string, err error) {
	if err == nil || r.ctx.Err() != nil || (errors.Is(err, net.ErrClosed) && !errors.Is(err, context.DeadlineExceeded)) {
		return
	}
	deliverEvent(marshalResult(result{ID: r.id, State: "transport_error", RequestID: capability.requestID, Profile: capability.profile,
		Error: safeError(fmt.Errorf("%s: %w", stage, err))}))
}

func (r *runtimeState) bridgeError(w http.ResponseWriter, capability *bridgeCapability, stage string, err error) {
	r.transportError(capability, stage, err)
	detail := strings.NewReplacer("\r", " ", "\n", " ").Replace(safeError(fmt.Errorf("%s: %w", stage, err)))
	if len(detail) > 2048 {
		detail = detail[:2048] + " [truncated; full cause in runtime diagnostics]"
	}
	w.Header().Set("X-Collector-Bridge-Error", detail)
	http.Error(w, "tailnet request failed", http.StatusBadGateway)
}
