/// <reference types="es4x.d.ts" />
// @ts-check

import {Router} from '@vertx/web';

import {PgClient, Tuple} from '@reactiverse/reactive-pg-client';
import {PgPoolOptions} from '@reactiverse/reactive-pg-client/options';
import {HandlebarsTemplateEngine} from '@vertx/web-templ-handlebars'

import {randomWorld, getQueries} from './util.mjs'

const SERVER = 'vertx.js';

const app = Router.router(vertx);
const template = HandlebarsTemplateEngine.create(vertx);
let date = new Date().toUTCString();

vertx.setPeriodic(1000, t => date = new Date().toUTCString());

/*
 * This test exercises the framework fundamentals including keep-alive support, request routing, request header
 * parsing, object instantiation, JSON serialization, response header generation, and request count throughput.
 */
app.get("/json").handler(ctx => {
  ctx.response()
    .putHeader("Server", SERVER)
    .putHeader("Date", date)
    .putHeader("Content-Type", "application/json")
    .end(JSON.stringify({message: 'Hello, World!'}));
});

const UPDATE_WORLD = "UPDATE world SET randomnumber=$1 WHERE id=$2";
const SELECT_WORLD = "SELECT id, randomnumber from WORLD where id=$1";
const SELECT_FORTUNE = "SELECT id, message from FORTUNE";

let client = PgClient.pool(
  vertx,
  new PgPoolOptions()
    .setCachePreparedStatements(true)
    .setMaxSize(1)
    .setHost('localhost')
    .setUser('benchmarkdbuser')
    .setPassword('benchmarkdbpass')
    .setDatabase('hello_world'));

/*
 * This test exercises the framework's object-relational mapper (ORM), random number generator, database driver,
 * and database connection pool.
 */
app.get("/db").handler(ctx => {
  client.preparedQuery(SELECT_WORLD, Tuple.of(randomWorld()), res => {
    if (res.succeeded()) {
      let resultSet = res.result().iterator();

      if (!resultSet.hasNext()) {
        ctx.fail(404);
        return;
      }

      let row = resultSet.next();

      ctx.response()
        .putHeader("Server", SERVER)
        .putHeader("Date", date)
        .putHeader("Content-Type", "application/json")
        .end(JSON.stringify({id: row.getInteger(0), randomNumber: row.getInteger(1)}));
    } else {
      ctx.fail(res.cause());
    }
  })
});

/*
 * This test is a variation of Test #2 and also uses the World table. Multiple rows are fetched to more dramatically
 * punish the database driver and connection pool. At the highest queries-per-request tested (20), this test
 * demonstrates all frameworks' convergence toward zero requests-per-second as database activity increases.
 */
app.get("/queries").handler(ctx => {
  let failed = false;
  let worlds = [];

  const queries = getQueries(ctx.request());

  for (let i = 0; i < queries; i++) {
    client.preparedQuery(SELECT_WORLD, Tuple.of(randomWorld()), ar => {
      if (!failed) {
        if (ar.failed()) {
          failed = true;
          ctx.fail(ar.cause());
          return;
        }

        // we need a final reference
        const row = ar.result().iterator().next();
        worlds.push({id: row.getInteger(0), randomNumber: row.getInteger(1)});

        // stop condition
        if (worlds.length === queries) {
          ctx.response()
            .putHeader("Server", SERVER)
            .putHeader("Date", date)
            .putHeader("Content-Type", "application/json")
            .end(JSON.stringify(worlds));
        }
      }
    });
  }
});

/*
 * This test exercises the ORM, database connectivity, dynamic-size collections, sorting, server-side templates,
 * XSS countermeasures, and character encoding.
 */
app.get("/fortunes").handler(ctx => {
  client.preparedQuery(SELECT_FORTUNE, ar => {

    if (ar.failed()) {
      ctx.fail(ar.cause());
      return;
    }

    let fortunes = [];
    let resultSet = ar.result().iterator();

    if (!resultSet.hasNext()) {
      ctx.fail(404);
      return;
    }

    while (resultSet.hasNext()) {
      let row = resultSet.next();
      fortunes.push({id: row.getInteger(0), message: row.getString(1)});
    }

    fortunes.push({id: 0, message: "Additional fortune added at request time."});

    fortunes.sort((a, b) => {
      let messageA = a.message;
      let messageB = b.message;
      if (messageA < messageB) {
        return -1;
      }
      if (messageA > messageB) {
        return 1;
      }
      return 0;
    });

    // and now delegate to the engine to render it.
    template.render({fortunes: fortunes}, "templates/fortunes.hbs", res => {
      if (res.succeeded()) {
        ctx.response()
          .putHeader("Server", SERVER)
          .putHeader("Date", date)
          .putHeader("Content-Type", "text/html; charset=UTF-8")
          .end(res.result());
      } else {
        ctx.fail(res.cause());
      }
    });
  });
});

/*
 * This test is a variation of Test #3 that exercises the ORM's persistence of objects and the database driver's
 * performance at running UPDATE statements or similar. The spirit of this test is to exercise a variable number of
 * read-then-write style database operations.
 */
app.route("/updates").handler(ctx => {
  let failed = false;
  let queryCount = 0;
  let worlds = [];

  const queries = getQueries(ctx.request());

  for (let i = 0; i < queries; i++) {
    const id = randomWorld();
    const index = i;

    client.preparedQuery(SELECT_WORLD, Tuple.of(id), ar => {
      if (!failed) {
        if (ar.failed()) {
          failed = true;
          ctx.fail(ar.cause());
          return;
        }

        const row = ar.result().iterator().next();

        worlds[index] = {id: row.getInteger(0), randomNumber: row.getInteger(1)};
        worlds[index].randomNumber = randomWorld();
        if (++queryCount === queries) {
          worlds.sort((a, b) => {
            return a.id - b.id;
          });

          let batch = [];

          worlds.forEach(world => {
            batch.push(Tuple.of(world.randomNumber, world.id));
          });

          client.preparedBatch(UPDATE_WORLD, batch, ar => {
            if (ar.failed()) {
              ctx.fail(ar.cause());
              return;
            }

            let json = [];
            worlds.forEach(world => {
              json.push({id: world.id, randomNumber: world.randomNumber});
            });

            ctx.response()
              .putHeader("Server", SERVER)
              .putHeader("Date", date)
              .putHeader("Content-Type", "application/json")
              .end(JSON.stringify(json));
          });
        }
      }
    });
  }
});

/*
 * This test is an exercise of the request-routing fundamentals only, designed to demonstrate the capacity of
 * high-performance platforms in particular. Requests will be sent using HTTP pipelining. The response payload is
 * still small, meaning good performance is still necessary in order to saturate the gigabit Ethernet of the test
 * environment.
 */
app.get("/plaintext").handler(ctx => {
  ctx.response()
    .putHeader("Server", SERVER)
    .putHeader("Date", date)
    .putHeader("Content-Type", "text/plain")
    .end('Hello, World!');
});

vertx
  .createHttpServer()
  .requestHandler(app)
  .listen(8080);

console.log('Server listening at: http://localhost:8080/');
