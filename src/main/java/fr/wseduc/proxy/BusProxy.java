/*
 * Copyright © WebServices pour l'Éducation, 2023
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

package fr.wseduc.proxy;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class BusProxy extends AbstractVerticle {

	private static final Logger log = LoggerFactory.getLogger(BusProxy.class);
	private EventBus eb;
	private Set<String> allowedBusAddress = new HashSet<>();
	private String basicString = UUID.randomUUID().toString();

	@Override
	public void start() throws Exception {
		super.start();

		eb = vertx.eventBus();
		final JsonObject config = config();
		basicString = "Basic " + config.getString("basic-authorization-header", UUID.randomUUID().toString());
		allowedBusAddress = config.getJsonArray("allowed-bus-address").stream().map(x -> ((String) x)).collect(Collectors.toSet());

		vertx.createHttpServer().requestHandler(new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				final String authHeader = request.headers().get("Authorization");
				if (basicString.equals(authHeader)) {
					forward(request);
				} else {
					request.response().setStatusCode(401).end();
				}
			}
		}).listen(config().getInteger("port", 18000));

	}

	private void forward(HttpServerRequest request) {
		request.bodyHandler(new Handler<Buffer>() {
			@Override
			public void handle(Buffer event) {
				try {
					final JsonObject json = new JsonObject(event.toString("UTF-8"));
					final String busAddress = json.getString("bus-address");
					if (allowedBusAddress.contains(busAddress)) {
						eb.request(busAddress, json.getJsonObject("message"), ar -> {
							if (ar.succeeded()) {
								request.response().end(((JsonObject) ar.result().body()).encode());
							} else {
								log.error("Error when call bus address " + busAddress, ar.cause());
								request.response().setStatusCode(400).end();
							}
						});
					} else {
						request.response().setStatusCode(403).end();
					}
				} catch (RuntimeException e) {
					log.warn(e.getMessage(), e);
					request.response().setStatusCode(500).end();
				}
			}
		});
	}

}
