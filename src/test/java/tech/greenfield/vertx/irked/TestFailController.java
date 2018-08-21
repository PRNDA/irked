package tech.greenfield.vertx.irked;

import org.junit.Before;
import org.junit.Test;

import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import tech.greenfield.vertx.irked.annotations.Endpoint;
import tech.greenfield.vertx.irked.annotations.Get;
import tech.greenfield.vertx.irked.annotations.OnFail;
import tech.greenfield.vertx.irked.base.TestBase;
import tech.greenfield.vertx.irked.status.BadRequest;

public class TestFailController extends TestBase {

	public class FailController extends Controller {
		@Get("/")
		WebHandler index = r -> {
			throw new RuntimeException();
		};
		
		@Get("/arg")
		WebHandler witharg = r -> {
			throw new IllegalArgumentException();
		};
		
		@OnFail(exceptions = {IllegalArgumentException.class})
		@Endpoint("/*")
		WebHandler invalidArgHandler = r -> {
			r.fail(new BadRequest(r.failure()));
		};
		
		@OnFail
		@Endpoint("/*")
		WebHandler failureHandler = r -> {
			r.sendJSON(new JsonObject().put("success", false), HttpError.toHttpError(r));
		};
	}

	@Before
	public void deployServer(TestContext context) {
		deployController(new FailController(), context.asyncAssertSuccess());
	}

	@Test
	public void testFail(TestContext context) {
		Async async = context.async();
		getClient().get(port, "localhost", "/").exceptionHandler(t -> context.fail(t)).handler(res -> {
			context.assertEquals(500, res.statusCode(), "Request failed");
			res.exceptionHandler(t -> context.fail(t)).bodyHandler(body -> {
				try {
					JsonObject o = body.toJsonObject();
					context.assertEquals(Boolean.FALSE, o.getValue("success"));
				} catch (Exception e) {
					context.fail(e);
				}
			});
			async.complete();
		}).end();
	}

	@Test
	public void testSelectiveFail(TestContext context) {
		Async async = context.async();
		getClient().get(port, "localhost", "/arg").exceptionHandler(t -> context.fail(t)).handler(res -> {
			context.assertEquals(400, res.statusCode(), "Request failed");
			res.exceptionHandler(t -> context.fail(t)).bodyHandler(body -> {
				try {
					JsonObject o = body.toJsonObject();
					context.assertEquals(Boolean.FALSE, o.getValue("success"));
				} catch (Exception e) {
					context.fail(e);
				}
			});
			async.complete();
		}).end();
	}
}
