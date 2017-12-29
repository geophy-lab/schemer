package schemer.registry.graphql

import java.nio.charset.StandardCharsets
import java.util.{Base64, UUID}

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import org.apache.spark.sql.SparkSession
import sangria.macros.derive.GraphQLField
import schemer._
import schemer.registry.actors._
import schemer.registry.dao.SchemaDao
import schemer.registry.models._
import schemer.registry.utils.Clock
import com.github.mauricio.async.db.postgresql.exceptions.GenericDatabaseException
import org.joda.time.DateTime
import schemer.registry.exceptions.{
  SchemerInferenceException,
  SchemerSchemaCreationException,
  SchemerSchemaVersionCreationException
}

import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class GraphQLService(
    schemaDao: SchemaDao,
    inferActor: ActorRef
)(
    implicit val spark: SparkSession,
    implicit val clock: Clock,
    implicit val ec: ExecutionContext,
    implicit val system: ActorSystem,
    implicit val inferActorTimeout: Timeout
) {

  def inferCSVSchema(options: CSVOptions, paths: Seq[String]) =
    inferWithActor(CSVSchemaInferenceRequest(options, paths))

  def inferJSONSchema(paths: Seq[String]) =
    inferWithActor(JSONSchemaInferenceRequest(paths))

  def inferParquetSchema(`type`: String, paths: Seq[String]) =
    inferWithActor(ParquetSchemaInferenceRequest(`type`, paths))

  def inferAvroSchema(paths: Seq[String]) =
    inferWithActor(AvroSchemaInferenceRequest(paths))

  @GraphQLField
  def addSchema(name: String, namespace: String, `type`: SchemaType, user: String) =
    schemaDao.create(Schema(name, namespace, `type`.`type`, clock.nowUtc, user)).recoverWith {
      case ex: GenericDatabaseException =>
        Future.failed(SchemerSchemaCreationException(ex.asInstanceOf[GenericDatabaseException].errorMessage.message))
      case ex =>
        Future.failed(SchemerSchemaCreationException(ex.getMessage))
    }

  @GraphQLField
  def addSchemaVersion(schemaId: UUID, version: String, schemaConfig: String, user: String) =
    schemaDao
      .find(schemaId)
      .flatMap {
        case Some(schema) =>
          schemaDao.createVersion(SchemaVersion(null, schema.id, version, schemaConfig, clock.nowUtc, user))
        case None => Future.failed(SchemerSchemaVersionCreationException(s"Schema with id $schemaId not found"))
      }
      .recoverWith {
        case ex: GenericDatabaseException =>
          Future.failed(
            SchemerSchemaVersionCreationException(ex.asInstanceOf[GenericDatabaseException].errorMessage.message)
          )
        case ex =>
          Future.failed(SchemerSchemaVersionCreationException(ex.getMessage))
      }

  def allSchemas = schemaDao.all()

  def schemaVersions(id: UUID, first: Int, after: Option[String]) = {
    val dateTimeFromCursor =
      after.map(a => new DateTime(new String(Base64.getDecoder.decode(a), StandardCharsets.UTF_8).toLong))

    val numberOfItems = Option(first).filter(_ <= 10).getOrElse(10)

    schemaDao
      .findVersions(id, numberOfItems, dateTimeFromCursor)
      .map { versions =>
        SchemaSchemaVersionConnection(
          PageInfo(true, true),
          versions.map { version =>
            val cursor =
              Base64.getEncoder.encodeToString(version.createdOn.getMillis.toString.getBytes(StandardCharsets.UTF_8))
            SchemaSchemaVersionEdge(cursor, version)
          }
        )
      }
  }

  def latestSchemaVersion(id: UUID) = schemaDao.findLatestVersion(id)

  def inferWithActor(message: Any) =
    (inferActor ? message).recoverWith {
      case ex: SchemerInferenceException =>
        Future.failed(ex)
      case _: AskTimeoutException =>
        Future.failed(SchemerInferenceException("Timeout while trying to infer schema"))
      case ex =>
        Future.failed(SchemerInferenceException(ex.getMessage))
    }
}
