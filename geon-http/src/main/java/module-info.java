/// A minimal HTTP/1.1 server on Netty: GET routes, query parameters, CORS and keep
/// alive, with handlers running on virtual threads.
///
/// Start with [ai.snaphop.geon.http.HttpServer#builder()].
module ai.snaphop.geon.http {
	exports ai.snaphop.geon.http;

	requires io.netty.buffer;
	requires io.netty.codec;
	requires io.netty.codec.http;
	requires io.netty.common;
	requires io.netty.transport;
	requires static org.jspecify;
}
