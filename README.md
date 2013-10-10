# Présentation

Module http proxy

Proxy configuration example :

    "proxies" : [
		{ "location" : "/", "proxy_pass": "http://localhost:8017" },
		{ "location" : "/admin", "proxy_pass": "http://localhost:8008" }
	]
