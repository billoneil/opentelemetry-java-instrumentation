/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package springdata

import static io.opentelemetry.auto.test.utils.TraceUtils.runUnderTrace
import static io.opentelemetry.trace.Span.Kind.CLIENT
import static io.opentelemetry.trace.Span.Kind.INTERNAL

import io.opentelemetry.auto.test.AgentTestRunner
import io.opentelemetry.trace.attributes.SemanticAttributes
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import spock.lang.Shared

class Elasticsearch53SpringRepositoryTest extends AgentTestRunner {
  // Setting up appContext & repo with @Shared doesn't allow
  // spring-data instrumentation to applied.
  // To change the timing without adding ugly checks everywhere -
  // use a dynamic proxy.  There's probably a more "groovy" way to do this.

  @Shared
  DocRepository repo = Proxy.newProxyInstance(
    getClass().getClassLoader(),
    [DocRepository] as Class[],
    new LazyProxyInvoker())

  static class LazyProxyInvoker implements InvocationHandler {
    def repo

    DocRepository getOrCreateRepository() {
      if (repo != null) {
        return repo
      }

      def applicationContext = new AnnotationConfigApplicationContext(Config)
      repo = applicationContext.getBean(DocRepository)

      return repo
    }

    @Override
    Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      return method.invoke(getOrCreateRepository(), args)
    }
  }

  def setup() {
    repo.refresh()
    TEST_WRITER.clear()
    runUnderTrace("delete") {
      repo.deleteAll()
    }
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.clear()
  }

  def "test empty repo"() {
    when:
    def result = repo.findAll()

    then:
    !result.iterator().hasNext()

    and:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "CrudRepository.findAll"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "SearchAction"
          kind CLIENT
          errored false
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "SearchAction"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" "doc"
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }

  def "test CRUD"() {
    when:
    def doc = new Doc()

    then:
    repo.index(doc) == doc

    and:
    assertTraces(1) {
      trace(0, 4) {
        span(0) {
          name "ElasticsearchRepository.index"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "IndexAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "IndexAction"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.response.status" 201
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
          }
        }
        span(2) {
          name "PutMappingAction"
          kind CLIENT
          childOf span(1)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "PutMappingAction"
            "elasticsearch.action" "PutMappingAction"
            "elasticsearch.request" "PutMappingRequest"
          }
        }
        span(3) {
          name "RefreshAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "RefreshAction"
            "elasticsearch.action" "RefreshAction"
            "elasticsearch.request" "RefreshRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.shard.broadcast.failed" 0
            "elasticsearch.shard.broadcast.successful" 5
            "elasticsearch.shard.broadcast.total" 10
          }
        }
      }
    }
    TEST_WRITER.clear()

    and:
    repo.findById("1").get() == doc

    and:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          name "CrudRepository.findById"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "GetAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "GetAction"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    doc.data = "other data"

    then:
    repo.index(doc) == doc
    repo.findById("1").get() == doc

    and:
    assertTraces(2) {
      trace(0, 3) {
        span(0) {
          name "ElasticsearchRepository.index"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "IndexAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "IndexAction"
            "elasticsearch.action" "IndexAction"
            "elasticsearch.request" "IndexRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.response.status" 200
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
          }
        }
        span(2) {
          name "RefreshAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "RefreshAction"
            "elasticsearch.action" "RefreshAction"
            "elasticsearch.request" "RefreshRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.shard.broadcast.failed" 0
            "elasticsearch.shard.broadcast.successful" 5
            "elasticsearch.shard.broadcast.total" 10
          }
        }
      }
      trace(1, 2) {
        span(0) {
          name "CrudRepository.findById"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "GetAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "GetAction"
            "elasticsearch.action" "GetAction"
            "elasticsearch.request" "GetRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.type" "doc"
            "elasticsearch.id" "1"
            "elasticsearch.version" Number
          }
        }
      }
    }
    TEST_WRITER.clear()

    when:
    repo.deleteById("1")

    then:
    !repo.findAll().iterator().hasNext()

    and:
    assertTraces(2) {
      trace(0, 3) {
        span(0) {
          name "CrudRepository.deleteById"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "DeleteAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "DeleteAction"
            "elasticsearch.action" "DeleteAction"
            "elasticsearch.request" "DeleteRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.write.type" "doc"
            "elasticsearch.request.write.version"(-3)
            "elasticsearch.shard.replication.failed" 0
            "elasticsearch.shard.replication.successful" 1
            "elasticsearch.shard.replication.total" 2
          }
        }
        span(2) {
          name "RefreshAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "RefreshAction"
            "elasticsearch.action" "RefreshAction"
            "elasticsearch.request" "RefreshRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.shard.broadcast.failed" 0
            "elasticsearch.shard.broadcast.successful" 5
            "elasticsearch.shard.broadcast.total" 10
          }
        }
      }

      trace(1, 2) {
        span(0) {
          name "CrudRepository.findAll"
          kind INTERNAL
          attributes {
          }
        }
        span(1) {
          name "SearchAction"
          kind CLIENT
          childOf span(0)
          attributes {
            "${SemanticAttributes.DB_SYSTEM.key()}" "elasticsearch"
            "${SemanticAttributes.DB_OPERATION.key()}" "SearchAction"
            "elasticsearch.action" "SearchAction"
            "elasticsearch.request" "SearchRequest"
            "elasticsearch.request.indices" indexName
            "elasticsearch.request.search.types" "doc"
          }
        }
      }
    }

    where:
    indexName = "test-index"
  }
}
