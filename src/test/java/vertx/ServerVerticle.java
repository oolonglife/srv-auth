package vertx;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import otocloud.auth.common.util.ShareableUtil;

/**
 * zhangyef@yonyou.com on 2015-11-12.
 */
public class ServerVerticle implements Verticle {
    private Vertx vertx;
    private Context context;

    @Override
    public Vertx getVertx() {
        return vertx;
    }

    @Override
    public void init(Vertx vertx, Context context) {
        this.vertx = vertx;
        this.context = context;
    }

    @Override
    public void start(Future<Void> startFuture) throws Exception {
//        HttpServer server = vertx.createHttpServer();
        Router mainRouter = ShareableUtil.getMainRouter(vertx);

        mainRouter.route(HttpMethod.GET, "/api/status").handler(context -> {
            context.request();
           context.response().end("ok");
        });

//        server.requestHandler(mainRouter::accept);
//        int port = 8081;
//        server.listen(port, ret -> {
//            if(ret.succeeded()){
//                System.out.println("ServerVerticle starts.");
//            }
//        });
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {

    }
}
