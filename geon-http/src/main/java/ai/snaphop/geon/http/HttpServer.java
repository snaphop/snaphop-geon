package ai.snaphop.geon.http;

import java.lang.System.Logger.Level;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

/// A small HTTP/1.1 server for `GET` routes.
///
/// Netty event loops do the network work; handlers run on virtual threads so a slow
/// query never blocks other connections. Paths are matched exactly after a trailing
/// slash is removed, so `/api/` and `/api` are the same route.
///
/// ```java
/// try (var server = HttpServer.builder()
///         .port(2322)
///         .route("/status", req -> HttpResponse.json(200, "{\"status\":\"Ok\"}"))
///         .start()) {
///     server.awaitClose();
/// }
/// ```
public final class HttpServer implements AutoCloseable {

	private static final System.Logger logger = System.getLogger(HttpServer.class.getName());

	private final EventLoopGroup group;

	private final ExecutorService executor;

	private final Channel channel;

	private HttpServer(EventLoopGroup group, ExecutorService executor, Channel channel) {
		this.group = group;
		this.executor = executor;
		this.channel = channel;
	}

	/// Starts configuring a server.
	/// @return a builder.
	public static Builder builder() {
		return new Builder();
	}

	/// The port the server listens on, useful when it was started on port 0.
	/// @return the port.
	public int port() {
		return ((InetSocketAddress) channel.localAddress()).getPort();
	}

	/// Blocks until the server is closed.
	/// @throws InterruptedException if interrupted while waiting.
	public void awaitClose() throws InterruptedException {
		channel.closeFuture().sync();
	}

	/// Stops accepting connections, lets running requests finish for up to two seconds
	/// and releases the threads.
	@Override
	public void close() {
		channel.close().syncUninterruptibly();
		group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
		executor.close();
	}

	/// Turns a handler failure into a response.
	@FunctionalInterface
	public interface ErrorHandler {

		/// @param request the request that failed.
		/// @param error what the handler threw.
		/// @return the response to send.
		HttpResponse handle(HttpRequest request, Throwable error);

	}

	/// Configures and starts an [HttpServer]. Not thread safe.
	public static final class Builder {

		private String host = "127.0.0.1";

		private int port = 2322;

		private final Map<String, HttpHandler> routes = new LinkedHashMap<>();

		private HttpHandler notFound = req -> HttpResponse.json(404,
				"{\"message\":\"Endpoint " + req.method() + " " + req.path() + " not found.\"}");

		private ErrorHandler errorHandler = (req, e) -> HttpResponse.json(500,
				"{\"message\":\"Internal server error.\"}");

		private Cors cors = Cors.NONE;

		private int maxRequestBytes = 64 * 1024;

		private Metrics metrics = new Metrics();

		private boolean metricsEnabled;

		private Builder() {
		}

		/// The address to listen on. Defaults to `127.0.0.1`.
		/// @param host an IP address or host name; `0.0.0.0` listens on all interfaces.
		/// @return this.
		public Builder host(String host) {
			this.host = host;
			return this;
		}

		/// The port to listen on. Defaults to `2322`, Photon's port. `0` picks a free port.
		/// @param port the port.
		/// @return this.
		public Builder port(int port) {
			this.port = port;
			return this;
		}

		/// Adds a `GET` route.
		/// @param path the exact path, for example `/api`.
		/// @param handler the handler.
		/// @return this.
		public Builder route(String path, HttpHandler handler) {
			routes.put(normalize(path), handler);
			return this;
		}

		/// The handler for requests that match no route.
		/// @param handler the handler.
		/// @return this.
		public Builder notFound(HttpHandler handler) {
			this.notFound = handler;
			return this;
		}

		/// What to send when a handler throws.
		/// @param errorHandler the error handler.
		/// @return this.
		public Builder errorHandler(ErrorHandler errorHandler) {
			this.errorHandler = errorHandler;
			return this;
		}

		/// Cross origin access. Defaults to [Cors#NONE].
		/// @param cors the policy.
		/// @return this.
		public Builder cors(Cors cors) {
			this.cors = cors;
			return this;
		}

		/// Records each request's path, status and latency. Off by default. Serve the
		/// result with a route to [Metrics#handler()], on this or another server.
		/// @param metrics where to record.
		/// @return this.
		public Builder metrics(Metrics metrics) {
			this.metrics = metrics;
			this.metricsEnabled = true;
			return this;
		}

		/// Binds the port and starts serving.
		/// @return the running server.
		/// @throws InterruptedException if interrupted while binding.
		public HttpServer start() throws InterruptedException {
			var group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
			var executor = Executors.newVirtualThreadPerTaskExecutor();
			var dispatcher = new Dispatcher(Map.copyOf(routes), notFound, errorHandler, cors, executor,
					metricsEnabled ? metrics : null);
			try {
				Channel channel = new ServerBootstrap().group(group)
					.channel(NioServerSocketChannel.class)
					.childHandler(new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(SocketChannel ch) {
							ch.pipeline()
								.addLast(new HttpServerCodec())
								.addLast(new HttpObjectAggregator(maxRequestBytes))
								.addLast(new Handler(dispatcher));
						}
					})
					.bind(host, port)
					.sync()
					.channel();
				return new HttpServer(group, executor, channel);
			}
			catch (InterruptedException | RuntimeException e) {
				group.shutdownGracefully();
				executor.close();
				throw e;
			}
		}

	}

	static String normalize(String path) {
		return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
	}

	private record Dispatcher(Map<String, HttpHandler> routes, HttpHandler notFound, ErrorHandler errorHandler,
			Cors cors, ExecutorService executor, @Nullable Metrics metrics) {

		HttpResponse dispatch(HttpRequest request) {
			long start = System.nanoTime();
			HttpHandler handler = request.method().equals("GET") ? routes.get(request.path()) : null;
			HttpResponse response = handle(handler, request);
			if (metrics != null) {
				// Unknown paths share one label so scanners cannot grow the metrics.
				metrics.record(handler == null ? "other" : request.path(), response.status(),
						System.nanoTime() - start);
			}
			return response;
		}

		private HttpResponse handle(@Nullable HttpHandler handler, HttpRequest request) {
			try {
				return (handler == null ? notFound : handler).handle(request);
			}
			catch (Throwable e) {
				try {
					return errorHandler.handle(request, e);
				}
				catch (RuntimeException inner) {
					logger.log(Level.ERROR, "Error handler failed for " + request.path(), inner);
					return HttpResponse.json(500, "{\"message\":\"Internal server error.\"}");
				}
			}
		}

	}

	private static final class Handler extends SimpleChannelInboundHandler<FullHttpRequest> {

		private final Dispatcher dispatcher;

		Handler(Dispatcher dispatcher) {
			this.dispatcher = dispatcher;
		}

		@Override
		protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) {
			boolean keepAlive = HttpUtil.isKeepAlive(msg);
			HttpHeaders headers = msg.headers().copy();
			String origin = headers.get(HttpHeaderNames.ORIGIN);
			Cors cors = dispatcher.cors();
			if (msg.method().equals(HttpMethod.OPTIONS) && cors.isEnabled()
					&& headers.contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD)) {
				write(ctx, preflight(headers, origin, cors), keepAlive);
				return;
			}
			var decoder = new QueryStringDecoder(msg.uri());
			var query = new LinkedHashMap<String, List<String>>();
			decoder.parameters().forEach((k, v) -> query.put(k, List.copyOf(v)));
			var request = new HttpRequest(msg.method().name(), normalize(decoder.path()),
					java.util.Collections.unmodifiableMap(query), headers::get);
			dispatcher.executor().execute(() -> {
				HttpResponse response = dispatcher.dispatch(request);
				var full = toNetty(response);
				String allow = cors.isEnabled() ? cors.allowOriginOrNull(origin) : null;
				if (allow != null) {
					full.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allow);
					full.headers().set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
				}
				write(ctx, full, keepAlive);
			});
		}

		private static DefaultFullHttpResponse preflight(HttpHeaders headers, @Nullable String origin, Cors cors) {
			var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
			String allow = cors.allowOriginOrNull(origin);
			if (allow != null) {
				response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, allow);
				response.headers().set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN);
				response.headers()
					.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS,
							headers.get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD));
				String requested = headers.get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
				if (requested != null) {
					response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, requested);
				}
			}
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
			return response;
		}

		private static DefaultFullHttpResponse toNetty(HttpResponse r) {
			var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(r.status()),
					Unpooled.wrappedBuffer(r.body()));
			response.headers().set(HttpHeaderNames.CONTENT_TYPE, r.contentType());
			response.headers().set(HttpHeaderNames.CONTENT_LENGTH, r.body().length);
			r.headers().forEach((k, v) -> response.headers().set(k, v));
			return response;
		}

		private static void write(ChannelHandlerContext ctx, DefaultFullHttpResponse response, boolean keepAlive) {
			if (keepAlive) {
				response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
				ctx.writeAndFlush(response);
			}
			else {
				ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
			}
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			logger.log(Level.DEBUG, "Closing connection after error", cause);
			ctx.close();
		}

	}

}
