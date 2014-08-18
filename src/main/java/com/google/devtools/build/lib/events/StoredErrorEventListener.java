// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.events;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores error and warning events, and later replays them. Thread-safe.
 */
public class StoredErrorEventListener implements ErrorEventListener {

  private final List<Event> events = new ArrayList<>();
  private boolean hasErrors;

  public synchronized ImmutableList<Event> getEvents() {
    return ImmutableList.copyOf(events);
  }

  /** Returns true if there are no stored events. */
  public synchronized boolean isEmpty() {
    return events.isEmpty();
  }

  @Override
  public synchronized void warn(Location location, String message) {
    events.add(new Event(EventKind.WARNING, location, message));
  }

  @Override
  public synchronized void error(Location location, String message) {
    hasErrors = true;
    events.add(new Event(EventKind.ERROR, location, message));
  }

  @Override
  public synchronized void info(Location location, String message) {
    events.add(new Event(EventKind.INFO, location, message));
  }

  @Override
  public synchronized void progress(Location location, String message) {
    events.add(new Event(EventKind.PROGRESS, location, message));
  }

  @Override
  public void report(EventKind kind, Location location, String message) {
    hasErrors |= kind == EventKind.ERROR;
    events.add(new Event(kind, location, message));
  }

  @Override
  public void report(EventKind kind, Location location, byte[] message) {
    hasErrors |= kind == EventKind.ERROR;
    events.add(new Event(kind, location, message));
  }

  @Override
  public boolean showOutput(String tag) {
    return true;
  }

  /**
   * Replay all events stored in this object on the given listener, in the same order.
   */
  public synchronized void replayOn(ErrorEventListener listener) {
    Event.replayEventsOn(listener, events);
  }

  /**
   * Returns whether any of the events on this objects were errors.
   */
  public synchronized boolean hasErrors() {
    return hasErrors;
  }

  public synchronized void clear() {
    events.clear();
    hasErrors = false;
  }
}
