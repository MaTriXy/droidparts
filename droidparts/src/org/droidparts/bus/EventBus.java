/**
 * Copyright 2016 Alex Yanchenko
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.droidparts.bus;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import android.os.Handler;
import android.os.Looper;

import org.droidparts.inner.WeakWrapper;
import org.droidparts.inner.ann.MethodSpec;
import org.droidparts.inner.ann.bus.ReceiveEventsAnn;

import static org.droidparts.inner.ClassSpecRegistry.getReceiveEventsSpecs;

public class EventBus {

	private static final String ALL = "__all__";
	private static final Object NULL = new Object();

	private static final Map<String, Map<EventReceiver<Object>, Boolean>> eventNameToReceivers = new ConcurrentHashMap<String, Map<EventReceiver<Object>, Boolean>>();
	private static final Map<String, Object> stickyEvents = new ConcurrentHashMap<String, Object>();

	public static void postEvent(String name) {
		postEvent(name, null);
	}

	public static void postEvent(String name, Object data) {
		runOnUiThread(new PostEventRunnable(name, data));
	}

	public static void postEventSticky(String name) {
		postEventSticky(name, null);
	}

	public static void postEventSticky(String name, Object data) {
		stickyEvents.put(name, (data == null) ? NULL : data);
		postEvent(name, data);
	}

	public static void clearStickyEvents(String... eventNames) {
		boolean allEvents = (eventNames.length == 0);
		if (allEvents) {
			stickyEvents.clear();
		} else {
			HashSet<String> nameSet = new HashSet<String>(Arrays.asList(eventNames));
			for (String eventName : stickyEvents.keySet()) {
				if (nameSet.contains(eventName)) {
					stickyEvents.remove(eventName);
					break;
				}
			}
		}
	}

	public static void registerReceiver(EventReceiver<?> receiver, String... eventNames) {
		@SuppressWarnings("unchecked")
		EventReceiver<Object> rec = (EventReceiver<Object>) receiver;
		boolean allEvents = (eventNames.length == 0);
		if (allEvents) {
			for (String name : stickyEvents.keySet()) {
				Object data = stickyEvents.get(name);
				rec.onEvent(name, (data == NULL) ? null : data);
			}
			receiversForEventName(ALL).put(rec, Boolean.FALSE);
		} else {
			for (String name : eventNames) {
				Object data = stickyEvents.get(name);
				if (data != null) {
					rec.onEvent(name, (data == NULL) ? null : data);
				}
			}
			for (String action : eventNames) {
				receiversForEventName(action).put(rec, Boolean.FALSE);
			}
		}
	}

	public static void unregisterReceiver(EventReceiver<?> receiver) {
		receiversForEventName(ALL).remove(receiver);
		for (String eventName : eventNameToReceivers.keySet()) {
			Map<EventReceiver<Object>, Boolean> receivers = eventNameToReceivers.get(eventName);
			receivers.remove(receiver);
			if (receivers.isEmpty()) {
				eventNameToReceivers.remove(eventName);
			}
		}
	}

	public static void registerAnnotatedReceiver(Object obj) {
		MethodSpec<ReceiveEventsAnn>[] specs = getReceiveEventsSpecs(obj.getClass());
		for (MethodSpec<ReceiveEventsAnn> spec : specs) {
			registerReceiver(new ReflectiveReceiver(obj, spec), spec.ann.names);
		}
	}

	public static void unregisterAnnotatedReceiver(Object obj) {
		for (Map<EventReceiver<Object>, Boolean> receivers : eventNameToReceivers.values()) {
			for (EventReceiver<Object> receiver : receivers.keySet()) {
				if (receiver instanceof ReflectiveReceiver) {
					if (obj == ((ReflectiveReceiver) receiver).getObj()) {
						receivers.remove(receiver);
					}
				}
			}
		}
	}

	private static Map<EventReceiver<Object>, Boolean> receiversForEventName(String name) {
		Map<EventReceiver<Object>, Boolean> map = eventNameToReceivers.get(name);
		if (map == null) {
			map = new ConcurrentHashMap<EventReceiver<Object>, Boolean>();
			eventNameToReceivers.put(name, map);
		}
		return map;
	}

	private static void runOnUiThread(Runnable r) {
		if (handler == null) {
			handler = new Handler(Looper.getMainLooper());
		}
		boolean success = handler.post(r);
		// a hack
		while (!success) {
			handler = null;
			runOnUiThread(r);
		}
	}

	private static Handler handler;

	private static class PostEventRunnable implements Runnable {

		private final String name;
		private final Object data;

		public PostEventRunnable(String name, Object data) {
			this.name = name;
			this.data = data;
		}

		@Override
		public void run() {
			HashSet<EventReceiver<Object>> receivers = new HashSet<EventReceiver<Object>>();
			receivers.addAll(receiversForEventName(ALL).keySet());
			receivers.addAll(receiversForEventName(name).keySet());
			for (EventReceiver<Object> rec : receivers) {
				rec.onEvent(name, data);
			}
		}
	}

	static final class ReflectiveReceiver extends WeakWrapper<Object> implements EventReceiver<Object> {

		private final MethodSpec<ReceiveEventsAnn> spec;

		ReflectiveReceiver(Object obj, MethodSpec<ReceiveEventsAnn> spec) {
			super(obj);
			this.spec = spec;
		}

		@Override
		public void onEvent(String name, Object data) {
			try {
				Object obj = getObj();
				switch (spec.paramTypes.length) {
					case 0:
						spec.method.invoke(obj);
						break;
					case 1:
						if (spec.paramTypes[0] == String.class) {
							spec.method.invoke(obj, name);
						} else {
							spec.method.invoke(obj, data);
						}
						break;
					default:
						spec.method.invoke(obj, name, data);
				}
			} catch (IllegalArgumentException e) {
				throw e;
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public int hashCode() {
			return super.hashCode() + spec.method.hashCode();
		}

	}

}
