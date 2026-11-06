package ai.snaphop.geon.http;

/// Handles requests for a route. Handlers run on virtual threads, so they may block.
@FunctionalInterface
public interface HttpHandler {

	/// Handles a request.
	/// @param request the request.
	/// @return the response.
	/// @throws Exception any failure; the server's error handler turns it into a response.
	HttpResponse handle(HttpRequest request) throws Exception;

}
