/*
 * Copyright © WebServices pour l'Éducation, 2014
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
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class HttpProxy extends AbstractVerticle {

	private static final Logger log = LoggerFactory.getLogger(HttpProxy.class);
	private final Map<String, HttpClient> proxies = new HashMap<>();

	@Override
	public void start() throws Exception {
		super.start();

		loadProxies();

		vertx.createHttpServer().requestHandler(new Handler<HttpServerRequest>() {
			@Override
			public void handle(HttpServerRequest request) {
				HttpClient proxy = findProxy(request);
				if (proxy != null) {
					forward(request, proxy);
				} else {
					request.response().setStatusCode(404).end();
				}
			}
		}).listen(config().getInteger("port", 8000));

		if (config().getJsonObject("bus-proxy") != null) {
			vertx.deployVerticle(BusProxy.class, new DeploymentOptions().setConfig(config().getJsonObject("bus-proxy")));
		}

	}

	private HttpClient findProxy(HttpServerRequest request) {
		HttpClient proxy = null;
		String path = request.path();
		if (path != null && !path.trim().isEmpty()) {
			int idx = path.indexOf('/', 1);
			if (idx > 0) {
				String prefix = path.substring(0, idx);
				if (proxies.containsKey(prefix)) {
					proxy = proxies.get(prefix);
				}
			} else {
				proxy = proxies.get(path);
			}
			if (proxy == null && proxies.containsKey("/")) {
				proxy = proxies.get("/");
			}
		}
		return proxy;
	}

	private void loadProxies() {
		for (Object o : config().getJsonArray("proxies")) {
			if (!(o instanceof JsonObject)) {
				continue;
			}
			JsonObject proxyConf = (JsonObject) o;
			String location = proxyConf.getString("location");
			try {
				URI proxyPass = new URI(proxyConf.getString("proxy_pass"));
				final HttpClientOptions options = new HttpClientOptions()
						.setDefaultHost(proxyPass.getHost())
						.setDefaultPort(proxyPass.getPort())
						.setMaxPoolSize(config().getInteger("poolSize", 32))
						.setKeepAlive(true)
						.setConnectTimeout(config().getInteger("timeout", 10000));
				HttpClient proxy = vertx.createHttpClient(options);
				proxies.put(location, proxy);
			} catch (URISyntaxException e) {
				log.error("Error when load proxy_pass.", e);
			}
		}
	}

	private void forward(final HttpServerRequest request, HttpClient proxy) {
		String uri = request.uri();
		if (uri.endsWith("%2F") || uri.endsWith("%2f")) {
			uri = uri.substring(0, uri.length() - 3);
		}
		if (log.isDebugEnabled()) {
			log.debug(uri);
			// log.debug(proxy.getHost() + ":" + proxy.getPort());
		}
		final HttpClientRequest proxyRequest = proxy.request(request.method(), uri,
				new Handler<HttpClientResponse>() {
					public void handle(HttpClientResponse cRes) {
						request.response().setStatusCode(cRes.statusCode());
						request.response().headers().addAll(cRes.headers());
						request.response().headers().remove("Content-Length");
						request.response().setChunked(true);
						cRes.handler(new Handler<Buffer>() {
							public void handle(Buffer data) {
								request.response().write(data);
							}
						});
						cRes.endHandler(new Handler<Void>() {
							public void handle(Void v) {
								request.response().end();
							}
						});
					}
				});
		proxyRequest.headers().addAll(request.headers());
		proxyRequest.putHeader("X-Forwarded-Host", request.headers().get("Host"));
		proxyRequest.putHeader("X-Forwarded-For", request.remoteAddress().host());
		request.response().headers().remove("Content-Length");
		proxyRequest.setChunked(true);
		request.handler(new Handler<Buffer>() {
			public void handle(Buffer data) {
				proxyRequest.write(data);
			}
		});
		request.endHandler(new Handler<Void>() {
			public void handle(Void v) {
				proxyRequest.end();
			}
		});
	}

}
