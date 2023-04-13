# Vertx Redis Reconnect Test

## Problem

The vertx redis reconnection demo introduced at [docs](https://vertx.io/docs/vertx-redis-client/java/#_connecting_to_redis) cannot work in the case when redis instances shutdown and get back to ready.

## Steps to Reproduce

1. start redis server in local machine
    - command: docker run -d --name redis-stack -p 6379:6379 -p 8001:8001 redis/redis-stack:latest
2. deploy main verticle
    - command: bash ./redeploy.sh
    - http server will listen at port 8080 after connecting redis successfully
3. make http request
    - command: curl -v localhost:8080
    - it will return Hello Vert.x!, redis ret: null, null is the return value from redis get operation
4. close the redis server
    - the redis connection will be marked as closed silently
    - and the exception handler of redis connection will not be called, thus the reconnect will not work accordingly
5. restart redis server
6. make http request again

    exception will be thrown by send api:
    ```text
    SEVERE: Unhandled exception
    java.lang.IllegalStateException: Connection is closed
    ```

    The code is:

    ```java
    @Override
    public Future<Response> send(final Request request) {
    //System.out.println("send()#" + this.hashCode());
    final Promise<Response> promise;

    if (closed) {
      // ======================================================
      // ========= exception will be thrown from here =========
      // ======= exception handler will not be called  =======
      // ======================================================
      throw new IllegalStateException("Connection is closed");
    }

    if (!((RequestImpl) request).valid()) {
      return Future.failedFuture("Redis command is not valid, check https://redis.io/commands");
    }

    final CommandImpl cmd = (CommandImpl) request.command();

    // tag this connection as tainted if needed
    context.execute(cmd, this::taintCheck);

    final boolean voidCmd = cmd.isPubSub();
    // encode the message to a buffer
    final Buffer message = ((RequestImpl) request).encode();
    // offer the handler to the waiting queue if not void command
    if (!voidCmd) {
      // we might have switch thread/context
      synchronized (waiting) {
        if (waiting.isFull()) {
          return Future.failedFuture("Redis waiting Queue is full");
        }
        // create a new promise bound to the caller not
        // the instance of this object (a.k.a. "context")
        promise = vertx.promise();
        waiting.offer(promise);
      }
    } else {
      // create a new promise bound to the caller not
      // the instance of this object (a.k.a. "context")
      promise = vertx.promise();
    }
    // write to the socket
    try {
      netSocket.write(message)
        // if the write fails, this connection enters a unknown state
        // which means it should be terminated
        .onFailure(this::fail)
        .onSuccess(ok -> {
          if (voidCmd) {
            // only on this case notify the promise
            if (!promise.tryComplete()) {
              // if the promise fail (e.g.: an client error forced a cleanup)
              // call the exception handler if any

              // ======================================================
              // ======= exception thrown from here will cause ========
              // =========== exception handler to be called  ==========
              // ======================================================
              if (onException != null) {
                context.execute(new IllegalStateException("Result is already complete: [" + promise + "]"), onException);
              }
            }
          }
        });
    } catch (RuntimeException err) {
      // is the socket in a broken state?
      context.execute(err, this::fail);
      promise.fail(err);
    }
    ```
