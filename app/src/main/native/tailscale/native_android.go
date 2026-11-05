package main

/*
#include <stdlib.h>
void collector_event(const char *value);
char *collector_interfaces(void);
*/
import "C"
import (
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"tailscale.com/net/netmon"
	"unsafe"
)

func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces)
	deliverEvent = func(value string) {
		text := C.CString(value)
		defer C.free(unsafe.Pointer(text))
		C.collector_event(text)
	}
}

func androidInterfaces() ([]netmon.Interface, error) {
	text := C.collector_interfaces()
	if text == nil {
		return nil, errors.New("Android network interface JNI call failed; see Android exception log")
	}
	defer C.free(unsafe.Pointer(text))
	var snapshot struct {
		Error            string `json:"error"`
		DefaultInterface string `json:"defaultInterface"`
		Interfaces       []struct {
			Name      string   `json:"name"`
			Index     int      `json:"index"`
			MTU       int      `json:"mtu"`
			Flags     uint     `json:"flags"`
			Addresses []string `json:"addresses"`
		} `json:"interfaces"`
	}
	if err := json.Unmarshal([]byte(C.GoString(text)), &snapshot); err != nil {
		return nil, fmt.Errorf("Android network snapshot: %w", err)
	}
	if snapshot.Error != "" {
		return nil, errors.New(snapshot.Error)
	}
	result := make([]netmon.Interface, 0, len(snapshot.Interfaces))
	for _, entry := range snapshot.Interfaces {
		if entry.Name == "" || entry.Index <= 0 || entry.MTU < 0 {
			return nil, errors.New("Invalid Android interface metadata")
		}
		item := netmon.Interface{Interface: &net.Interface{Name: entry.Name, Index: entry.Index, MTU: entry.MTU, Flags: net.Flags(entry.Flags)}, AltAddrs: make([]net.Addr, 0, len(entry.Addresses))}
		for _, address := range entry.Addresses {
			ip, subnet, err := net.ParseCIDR(address)
			if err != nil {
				return nil, fmt.Errorf("Android interface address: %w", err)
			}
			subnet.IP = ip
			item.AltAddrs = append(item.AltAddrs, subnet)
		}
		result = append(result, item)
	}
	netmon.UpdateLastKnownDefaultRouteInterface(snapshot.DefaultInterface)
	return result, nil
}

//export collector_call
func collector_call(operation, request *C.char) *C.char {
	return C.CString(command(C.GoString(operation), C.GoString(request)))
}

//export collector_free
func collector_free(value *C.char) { C.free(unsafe.Pointer(value)) }
