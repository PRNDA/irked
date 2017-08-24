# Irked Vert.X Web Framework

Irked is a very opinionated framework for configuring Vert.X-web routing and call dispatch.

It allows you to write your REST API code without writing routing boiler plate by leveraging
annotations, auto-discovery through reflection and optionally (if you're into that as well)
dependency injection.

## Installation

In your `pom.xml` file, add the repository for Irked (we are currently not hosted
in the public Maven repository) as an element under `<project>`:

```
<repositories>
  <repository>
    <id>cloudonix-dist</id>
    <url>http://cloudonix-dist.s3-website-us-west-1.amazonaws.com/maven2/releases</url>
  </repository>
</repositories>
```

Then add Irked as a dependency:

```
<dependency>
	<groupId>tech.greenfield</groupId>
	<artifactId>irked-vertx</artifactId>
	<version>[0,)</version>
</dependency>
```

## Usage

Under Irked we use the concept of a "Controller" - a class whose fields and methods are used as
handlers for routes and that will handle incoming HTTP requests from `vertx-web`.

A "master controller" is created to define the root of the URI hierarchy - all configured routes
on that controller will be parsed relative to the root of the host.

### Simple Routing

To publish routes to the server's "Request Handler", create your controller class by extending the
irked `Controller` class, define fields or methods to handle HTTP requests and annotate them with
the relevant method annotations and URIs that those handlers should receive requests for.

#### A Sample Controller

```
package com.example.api;

import tech.greenfield.vertx.irked.*
import tech.greenfield.vertx.irked.status.*;

class Root extends Controller {

	@Get("/")
	Handler<RoutingContext> index = r -> {
		r.response().setStatusCode(200).end("Hello World!");
	};
	
	@Post("/")
	void create(Request r) {
		// the irked Request object offers some useful helper methods over the
		// standard Vert.x RoutingContext
		r.sendError(new BadRequest("Creating resources is not yet implemented"));
	}
}
```

### Sub Controllers

Complex routing topologies can be implemented by "mounting" sub-controllers under
the main controller - by setting fields to additional `Controller` implementations and annotating
them with the `@Endpoint` annotation with the URI set to the endpoint you want your sub-controller
to be accessible under.

### A Sample Main and Sub Controllers

```
package com.example.api;

import tech.greenfield.vertx.irked.*

class Root extends Controller {

	@Endpoint("/blocks")
	BlockApi blocks = new BlockApi();
	
}
```

```
package com.example.api;

import tech.greenfield.vertx.irked.*

class BlockApi extends Controller {

	@Get("/:id")
	Handler<Request> retrieve = r -> {
		// irked supports vertx-web path parameters 
		r.sendJSON(loadBlock(r.pathParam("id")));
	};
}
```

### Request Context Re-programming

As hinted above, irked supports path parameters using Vert.X web, but 
[unlike Vert.x web's sub-router](https://github.com/vert-x3/vertx-web/blob/master/vertx-web/src/main/java/io/vertx/ext/web/impl/RouterImpl.java#L269),
irked controllers support path parameters everywhere, including as base paths for mounting sub-controllers.

As a result, a sub-controller might be interested in reading data from a path parameter defined in
a parent controller, such that the sub-controller has no control over the definition. To promote
object oriented programming and with good encapsulation, irked allows parent controllers to provide
access to parameter (and other) data by "re-programming" the routing context that is passed to
sub-controllers.

A parent controller can define path parameters and then extract the data and hand it down to local
handlers and sub-controllers through a well defined API, by overriding the
`Controller.getRequestContext()` method.

#### A Sample Route Context Re-programming

```
package com.example.api;

import tech.greenfield.vertx.irked.*

class MyRequest extend Request {

	String id;

	public MyRequest(Request req) {
		super(req);
		id = req.pathParam("id");
	}
	
	public String getId() {
		return id;
	}
	
}
```

```
package com.example.api;

import tech.greenfield.vertx.irked.*
import tech.greenfield.vertx.irked.status.*;

class Root extends Controller {

	@Get("/:id")
	void report(MyRequest r) {
		r.response(new OK()).end(createReport(r.getId()));
	}

	@Endpoint("/:id/blocks")
	BlockApi blocks = new BlockApi();
	
	@Override
	protected MyRequest getRequestContext(Request req) {
		return new MyRequest(req);
	}
}
```

```
package com.example.api;

import tech.greenfield.vertx.irked.*

class BlockApi extends Controller {

	@Get("/")
	Handler<MyRequest> retrieve = r -> {
		r.sendJSON(getAllBlocksFor(r.getId()));
	};
}
```

### Cascading Request Handling

Sometimes its useful to have the multiple handlers handle the same request - for example you
may have a REST API that supports both PUT requests to update data and GET requests on the same
URI to retrieve such data. Supposed the response to the PUT request looks identical to the response
for a GET request - it just shows how the data looks after the update - so wouldn't it be better
if the same handler that handles the GET request also handles the output for the PUT request?

This is not an irked feature, but irked allows you to use all the power of Vert.X web, though there
is a small "gotcha" here that we should note - the order of the handlers definition is important,
and is the order they will be called:

#### A Sample Cascading Request Handlers

```
package com.example.api;

import tech.greenfield.vertx.irked.*
import tech.greenfield.vertx.irked.status.*;

class Root extends Controller {

	@Endpoint("/*")
	BodyHandler bodyHandler = BodyHandler.create();

	@Put("/")
	WebHandler update = r -> {
		// start an async operation to store the new data
		Future<Void> f = Future.future();
		store(r.pathParam("id"), r.getBodyAsJson(), f.completer());
		f.setHandler(res -> { // once the operation completes
			if (res.failed()) // if it failed
				r.sendError(new InternalServerError(res.cause())); // send an 500 error
			else // but if it succeeds
				r.next(); // we don't send a response - we stop handling the request and let
				// the next handler send the response
		});
	};
	
	@Put("/")
	@Get("/")
	WebHandler retrieve = r -> {
		r.sendJSON(data);
	};

}
```

You can of course pass data between handlers using the `RoutingContext`'s `put()`, `get()` and
`data()` methods as you do normally in Vert.X.

**Important note**: request cascading only works when degining handlers as handler _fields_. Using
methods is not supported because the JVM reflection API doesn't keep the order of methods, while it
does keep the order for fields.

### Handle Failures

It is often useful to move failure handling away from the request handler - to keep the code clean
and unify error handling which is often very repetitive. Irked supports 
[Vert.X web's error handling](http://vertx.io/docs/vertx-web/js/#_error_handling) using the `@OnFail`
annotation that you can assign a request handler. Note: the request handler still needs to be
configured properly for a URI and HTTP method - so we often find it useful to use the catch all
`@Endpoint` annotation with a wild card URI to configure the failure handler:

#### A Failure Handler Sample

```
package com.example.api;

import tech.greenfield.vertx.irked.*
import tech.greenfield.vertx.irked.status.*;

class Root extends Controller {

	@OnFail
	@Endpoint("/*")
	void failureHandler(Request r) {
		r.sendError(new InternalServerError(r.failure()));
	}
}
```

Irked's `Request.sendError()` creates an HTTP response with the correct error status and sends
it with an `application/json` body with a JSON object containing the fields "`status`" set
to `false` and "`message`" set to the exception's detail message.

Also see the tips section below for a more complex failure handler that may be useful.

### Initializing

After creating your set of `Controller` implementations, deploy them to Vert.x by setting up
a `Verticle` in the standard way, and set the HTTP request handler for the HTTP server by
asking Irked to set up the request handler.

#### Sample Vert.x HTTP Server

```
Future<HttpServer> async = Future.future();
vertx.createHttpServer()
		.requestHandler(new Irked(vertx).setupRequestHandler(new com.example.api.Root()))
		.listen(port, async);
```


### Tips

#### Mounting Middle-Ware

Under Vert.x its often useful to have a "middle-ware" that parse all your requests, for example:
the [Vert.x Web BodyHandler](https://github.com/vert-x3/vertx-examples/blob/master/web-examples/src/main/java/io/vertx/example/web/rest/SimpleREST.java#L50)
implementation reads the HTTP request body and handles all kinds of body formats for you.

This type of middle-ware can be easily used in irked by registering it on a catch all end-point,
very similar to how you set it up using the Vert.X web's `Router` implementation. In your root
controller, add a field like this:

```
@Endpoint("/*")
BodyHandler bodyHandler = BodyHandler.create();
```

This will cause all requests to first be captured by the `BodyHandler` before being passed to
other handlers.

#### Easily Pass Business Logic Errors To Clients

Sometimes it is necessary for the REST API to actually generate error responses to communicate
erroneous conditions to the client - such as missing authentication, invalid input, etc - using
the HTTP semantics by returning a response with some standard non-OK HTTP status.

In such cases, instead of hand crafting a response and doing a collection of `if...else`s to
make sure processing doesn't continue, irked allows you to take advantage of Java exception 
handling and built-in Vert.x web failure handling functionality to make this a breeze:

For example a handler might want to to signal that the expected content isn't there by returning
a 404 Not Found error:

```
@Get("/:id")
Handler<Request> retrieve = r -> {
	if (!existsItem(r.pathParam("id")))
		throw new NotFound("No such item!").uncheckedWrap();
	r.sendJSON(load(r.pathParam("id")));
}
```

The `HttpError.uncheckedWrap()` method wraps the business logic's HTTP status exception with a
`RuntimeException` so it can jump out of lambdas and other non-declaring code easily without
boiler-plate code. The Vert.X web request handling glue will pick up the exception and deliver it
to an appropriate `@OnFail` handler.

Then the `@OnFail` handler can be configured to automatically forward this status to the client:

```
@OnFail
@Endpoint("/*")
Handler<Request> failureHandler = r -> {
	r.sendError(HttpError.toHttpError(r));
};
```

The `HttpError.toHttpError()` helper method detects `RuntimeException`s and unwrap their content
automatically. If the underlying cause is an `HttpError`, it will deliver it to be sent using
`Request.sendError()`, otherwise it will create an `InternalServerError` exception (HTTP status 500)
that contains the unexpected exception, which will also be reported using `Request.sendError()`.
