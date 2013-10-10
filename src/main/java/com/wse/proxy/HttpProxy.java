package com.wse.proxy;

import org.vertx.java.core.Handler;
import org.vertx.java.core.VoidHandler;
import org.vertx.java.core.buffer.Buffer;
import org.vertx.java.core.http.HttpClient;
import org.vertx.java.core.http.HttpClientRequest;
import org.vertx.java.core.http.HttpClientResponse;
import org.vertx.java.core.http.HttpServerRequest;
import org.vertx.java.core.json.JsonObject;
import org.vertx.java.platform.Verticle;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class HttpProxy extends Verticle {

	private final Map<String, HttpClient> proxies = new HashMap<>();

	@Override
	public void start() {
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
		}).listen(container.config().getInteger("port", 8000));

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
		for (Object o : container.config().getArray("proxies")) {
			if (!(o instanceof JsonObject)) {
				continue;
			}
			JsonObject proxyConf = (JsonObject) o;
			String location = proxyConf.getString("location");
			try {
				URI proxyPass = new URI(proxyConf.getString("proxy_pass"));
				HttpClient proxy = vertx.createHttpClient()
						.setHost(proxyPass.getHost())
						.setPort(proxyPass.getPort())
						.setMaxPoolSize(container.config().getInteger("poolSize", 32))
						.setKeepAlive(true)
						.setConnectTimeout(container.config().getInteger("timeout", 10000));
				proxies.put(location, proxy);
			} catch (URISyntaxException e) {
				container.logger().error("Error when load proxy_pass.", e);
			}
		}
	}

	private void forward(final HttpServerRequest request, HttpClient proxy) {
		String uri = request.uri();
		if (uri.endsWith("%2F") || uri.endsWith("%2f")) {
			uri = uri.substring(0, uri.length() - 3);
		}
		if (container.logger().isDebugEnabled()) {
			container.logger().debug(uri);
			container.logger().debug(proxy.getHost() + ":" + proxy.getPort());
		}
		final HttpClientRequest proxyRequest = proxy.request(request.method(), uri,
				new Handler<HttpClientResponse>() {
					public void handle(HttpClientResponse cRes) {
						request.response().setStatusCode(cRes.statusCode());
						request.response().headers().set(cRes.headers());
						request.response().setChunked(true);
						cRes.dataHandler(new Handler<Buffer>() {
							public void handle(Buffer data) {
								request.response().write(data);
							}
						});
						cRes.endHandler(new VoidHandler() {
							public void handle() {
								request.response().end();
							}
						});
					}
				});
		proxyRequest.headers().set(request.headers());
		proxyRequest.putHeader("X-Forwarded-Host", request.headers().get("Host"));
		proxyRequest.putHeader("X-Forwarded-For", request.remoteAddress().getHostName());
		proxyRequest.setChunked(true);
		request.dataHandler(new Handler<Buffer>() {
			public void handle(Buffer data) {
				proxyRequest.write(data);
			}
		});
		request.endHandler(new VoidHandler() {
			public void handle() {
				proxyRequest.end();
			}
		});
	}

}
