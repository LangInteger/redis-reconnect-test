package io.vertx.starter;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainVerticle extends AbstractVerticle {

  private static final int MAX_RECONNECT_RETRIES = 16;

  private final RedisOptions options = new RedisOptions(
    JsonObject.of(
      "connectionString",
      "redis://10.165.36.34:6379",
      "maxPoolWaiting", 1000));
  private RedisConnection client;
  private final AtomicBoolean CONNECTING = new AtomicBoolean();

  @Override
  public void start() {
    createRedisClient()
      .onSuccess(connect -> {
        System.out.println("redis connected");
        client = connect;
        vertx.createHttpServer()
          .requestHandler(req -> {
            client.send(Request.cmd(Command.GET, "aa"))
              .compose(redisRet -> {
                req.response().end("Hello Vert.x!, redis ret: " + redisRet);
                return Future.succeededFuture();
              })
              .onFailure(exception -> {
                req.response().end("Hello Vert.x!, redis ret error " + exception.getMessage());
                System.out.println("exception in request: " + exception.getMessage());
              });
          })
          .listen(8080);
      })
      .onFailure(exception -> {
        System.out.println("exception in start verticle: " + exception.getMessage());
      });
  }

  // copy from https://vertx.io/docs/vertx-redis-client/java/#_implementing_reconnect_on_error

  /**
   * Will create a redis client and setup a reconnect handler when there is an exception in the
   * connection.
   */
  private Future<RedisConnection> createRedisClient() {
    Promise<RedisConnection> promise = Promise.promise();

    if (CONNECTING.compareAndSet(false, true)) {
      Redis.createClient(vertx, options)
        .connect()
        .onSuccess(conn -> {

          // make sure to invalidate old connection if present
          if (client != null) {
            client.close();
          }

          // make sure the client is reconnected on error
          conn.exceptionHandler(e -> {
            System.out.println("start reconnect ....");
            // attempt to reconnect,
            // if there is an unrecoverable error
            attemptReconnect(0);
          });

          // allow further processing
          promise.complete(conn);
          CONNECTING.set(false);
        }).onFailure(t -> {
          promise.fail(t);
          CONNECTING.set(false);
        });
    } else {
      promise.complete();
    }

    return promise.future();
  }

  /**
   * Attempt to reconnect up to MAX_RECONNECT_RETRIES
   */
  private void attemptReconnect(int retry) {
    if (retry > MAX_RECONNECT_RETRIES) {
      // we should stop now, as there's nothing we can do.
      CONNECTING.set(false);
    } else {
      // retry with backoff up to 10240 ms
      long backoff = (long) (Math.pow(2, Math.min(retry, 10)) * 10);

      vertx.setTimer(backoff, timer -> {
        createRedisClient()
          .onFailure(t -> attemptReconnect(retry + 1));
      });
    }
  }
}

