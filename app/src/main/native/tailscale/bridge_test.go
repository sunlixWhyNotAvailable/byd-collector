package main

import (
	"context"
	"errors"
	"testing"
	"time"
)

// Exercise the production ownership/cancellation boundary without substituting
// tsnet delivery. These checks do not claim authenticated network connectivity.
func TestReleaseCancelsOnlyItsBridgeRequests(t *testing.T) {
	root, closeRuntime := context.WithCancel(context.Background())
	defer closeRuntime()
	first, releaseFirst := context.WithCancel(root)
	second, releaseSecond := context.WithCancel(root)
	defer releaseSecond()
	requestLease := &bridgeCapability{ctx: first, cancel: releaseFirst, influx: true}
	mqttLease := &bridgeCapability{ctx: second, cancel: releaseSecond}
	r := &runtimeState{ctx: root, capabilities: map[string]*bridgeCapability{"influx": requestLease, "mqtt": mqttLease}}
	request, doneRequest := requestLease.requestContext(context.Background())
	defer doneRequest()
	mqtt, doneMqtt := mqttLease.requestContext(context.Background())
	defer doneMqtt()
	r.releaseCapability("influx")
	select {
	case <-request.Done():
		if !errors.Is(request.Err(), context.Canceled) {
			t.Fatal(request.Err())
		}
	case <-time.After(time.Second):
		t.Fatal("released native request remained active")
	}
	if mqtt.Err() != nil {
		t.Fatal("unrelated MQTT lease was cancelled")
	}
	r.releaseCapability("influx") // Cleanup is idempotent.
	closeRuntime()
	select {
	case <-mqtt.Done():
	case <-time.After(time.Second):
		t.Fatal("runtime close retained an active lease")
	}
}

func TestInfluxDeadlineFinishesBeforeOuterReadBudget(t *testing.T) {
	root, closeRuntime := context.WithCancel(context.Background())
	defer closeRuntime()
	lease := &bridgeCapability{ctx: root, influx: true}
	request, cancel := lease.requestContext(context.Background())
	defer cancel()
	started := time.Now()
	select {
	case <-request.Done():
		if !errors.Is(request.Err(), context.DeadlineExceeded) {
			t.Fatal(request.Err())
		}
		if elapsed := time.Since(started); elapsed < 14*time.Second || elapsed >= 20*time.Second {
			t.Fatalf("native request expired outside the agreed inner budget: %v", elapsed)
		}
	case <-time.After(20 * time.Second):
		t.Fatal("native request outlived Java read budget")
	}
	mqtt, stopMqtt := (&bridgeCapability{ctx: root}).requestContext(context.Background())
	defer stopMqtt()
	if _, bounded := mqtt.Deadline(); bounded {
		t.Fatal("HTTP deadline applied to MQTT")
	}
}
