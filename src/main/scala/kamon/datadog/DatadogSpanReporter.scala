package kamon.datadog

import java.time.Duration

import com.typesafe.config.Config
import kamon.trace.Span
import kamon.Kamon
import kamon.module.SpanReporter
import kamon.tag.{ Lookups, Tag }
import org.slf4j.LoggerFactory
import play.api.libs.json.{ JsObject, Json }

trait KamonDataDogTranslator {
  def translate(span: Span.Finished): DdSpan
}

object KamonDataDogTranslatorDefault extends KamonDataDogTranslator {
  def translate(span: Span.Finished): DdSpan = {
    val traceId = BigInt(span.trace.id.string, 16)
    val spanId = BigInt(span.id.string, 16)

    val parentId = if (span.parentId.isEmpty) None else Some(BigInt(span.parentId.string, 16))

    val name = span.tags.get(Lookups.option("component"))
      .getOrElse("kamon.trace")
    val resource = span.operationName
    val service = Kamon.environment.service
    val from = span.from
    val start = from.getEpochNano
    val duration = Duration.between(from, span.to)
    val marks = span.marks.map { m => m.key -> m.instant.getEpochNano.toString }.toMap
    val tags = (span.tags.all() ++ span.metricTags.all()).map { t =>
      t.key -> Tag.unwrapValue(t).toString
    }
    val meta = marks ++ tags
    new DdSpan(traceId, spanId, parentId, name, resource, service, "custom", start, duration, meta, span.hasError)

  }
}

class DatadogSpanReporter extends SpanReporter {
  private val translator: KamonDataDogTranslator = KamonDataDogTranslatorDefault
  private val logger = LoggerFactory.getLogger(classOf[DatadogAPIReporter])
  final private val httpConfigPath = "kamon.datadog.trace.http"
  private var httpClient = new HttpClient(Kamon.config().getConfig(httpConfigPath))

  override def reportSpans(spans: Seq[Span.Finished]): Unit = if (spans.nonEmpty) {
    val spanList: List[Seq[JsObject]] = spans
      .map(span => translator.translate(span).toJson())
      .groupBy { _.\("trace_id").get.toString() }
      .values
      .toList
    httpClient.doJsonPut(Json.toJson(spanList))
  }

  override def start(): Unit = {
    logger.info("Started the Kamon DataDog reporter")
  }

  override def stop(): Unit = {
    logger.info("Stopped the Kamon DataDog reporter")
  }

  override def reconfigure(config: Config): Unit = {
    logger.info("Reconfigured the Kamon DataDog reporter")
    httpClient = new HttpClient(config.getConfig(httpConfigPath))
  }

}