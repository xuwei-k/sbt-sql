package xerial.sbt.sql

import java.sql.JDBCType
import java.util.Properties

import sbt.{File, IO, _}

case class Schema(columns: Seq[Column])
case class Column(qname: String, reader:ColumnAccess, sqlType: java.sql.JDBCType, isNullable: Boolean, elementType: java.sql.JDBCType = JDBCType.NULL) {
  def packMethod(packerName:String) : String = {
    reader match {
      case BooleanColumn =>
        s"${packerName}.packBoolean(${qname})"
      case IntColumn =>
        s"${packerName}.packInt(${qname})"
      case LongColumn =>
        s"${packerName}.packLong(${qname})"
      case FloatColumn =>
        s"${packerName}.packFloat(${qname})"
      case DoubleColumn =>
        s"${packerName}.packDouble(${qname})"
      case StringColumn =>
        s"${packerName}.packString(${qname})"
      case ArrayColumn =>
        s"""{
           |  val arr = v.getArray()
           |
           |  ${packerName}.packArrayHeader(2)
           |}
         """.stripMargin
      case MapColumn =>
        // TODO
        ""
    }
  }
}

case class GeneratorConfig(sqlDir:File, targetDir:File, resourceTargetDir:File)

object SQLModelClassGenerator extends xerial.core.log.Logger {

  lazy val getBuildTime : Long = {
    val p = new Properties()
    val in = this.getClass.getResourceAsStream("/org/xerial/sbt/sbt-sql/build.properties")
    if(in != null) {
      try {
        p.load(in)
      }
      finally {
        in.close
      }
    }
    else {
      warn("build.properties file not found")
    }
    p.getProperty("build_time", System.currentTimeMillis().toString).toLong
  }
}

class SQLModelClassGenerator(jdbcConfig: JDBCConfig) extends xerial.core.log.Logger {
  private val db = new JDBCClient(jdbcConfig)

  protected val typeMapping = SQLTypeMapping.default

  private def wrapWithLimit0(sql: String) = {
    s"""SELECT * FROM (
       |${sql}
       |)
       |LIMIT 0""".stripMargin
  }

  def checkResultSchema(sql: String): Schema = {
    db.withConnection {conn =>
      db.submitQuery(conn, sql) {rs =>
        val m = rs.getMetaData
        val cols = m.getColumnCount
        val colTypes = (1 to cols).map {i =>
          val name = m.getColumnName(i)
          val qname = name match {
            case "type" => "`type`"
            case _ => name
          }
          val tpe = m.getColumnType(i)
          val jdbcType = JDBCType.valueOf(tpe)
          val elementType = if(tpe == JDBCType.ARRAY) {

          }
          else
            JDBCType.NULL

          val reader = typeMapping(jdbcType)
          val nullable = m.isNullable(i) != 0
          Column(qname, reader, jdbcType, nullable)
        }
        Schema(colTypes.toIndexedSeq)
      }
    }
  }

  def generate(config:GeneratorConfig) : Seq[(File, File)] = {
    // Submit queries using multi-threads to minimize the waiting time
    val result = Seq.newBuilder[(File, File)]
    val buildTime = SQLModelClassGenerator.getBuildTime
    info(s"SQLModelClassGenerator buildTime:${buildTime}")

    for (sqlFile <- (config.sqlDir ** "*.sql").get.par) {
      val path = sqlFile.relativeTo(config.sqlDir).get.getPath
      val targetFile = config.resourceTargetDir / path
      val targetClassFile = config.targetDir / path.replaceAll("\\.sql$", ".scala")

      info(s"Processing ${sqlFile.relativeTo(config.sqlDir).getOrElse(sqlFile)}")
      val latestTimestamp = Math.max(sqlFile.lastModified(), buildTime)
      if(targetFile.exists()
        && targetClassFile.exists()
        && latestTimestamp <= targetFile.lastModified()
        && latestTimestamp <= targetClassFile.lastModified()) {
        info(s"${targetFile.relativeTo(config.targetDir).getOrElse(targetFile)} is up-to-date")
      }
      else {
        info(s"Generating ${targetFile} (${targetFile.lastModified()}), ${targetClassFile} (${targetClassFile.lastModified()})")
        val sql = IO.read(sqlFile)
        val template = SQLTemplate(sql)
        val limit0 = wrapWithLimit0(template.populated)
        val schema = checkResultSchema(limit0)
        //info(s"template:\n${template.noParam}")
        //info(schema)

        // Write SQL template without type annotation
        IO.write(targetFile, template.noParam)
        val scalaCode = schemaToClass(sqlFile, config.sqlDir, schema, template)
        IO.write(targetClassFile, scalaCode)
        targetFile.setLastModified(latestTimestamp)
        targetClassFile.setLastModified(latestTimestamp)
      }

      synchronized {
        result += ((targetClassFile, targetFile))
      }
    }
    result.result()
  }


  def schemaToParamDef(schema:Schema) = {
    schema.columns.map {c =>
      s"val ${c.qname}:${c.reader.name}"
    }
  }

  def schemaToResultSetReader(schema:Schema) = {
    schema.columns.zipWithIndex.map { case (c, i) =>
      s"rs.${c.reader.rsMethod}(${i+1})"
    }
  }

  def schemaToColumNList(schema:Schema) = {
    schema.columns.map(_.qname)
  }

  def schemaToPackerCode(schema:Schema, packerName:String = "packer") = {
    for(c <- schema.columns) {
      s"${packerName}.packXXX(${c.qname})"
    }
  }

  def schemaToClass(origFile: File, baseDir: File, schema: Schema, sqlTemplate:SQLTemplate): String = {
    val packageName = origFile.relativeTo(baseDir).map {f =>
      Option(f.getParent).map(_.replaceAll("""[\\/]""", ".")).getOrElse("")
    }.getOrElse("")
    val name = origFile.getName.replaceAll("\\.sql$", "")

    val params = schemaToParamDef(schema)
    val rsReader = schemaToResultSetReader(schema)
    val columnList = schemaToColumNList(schema)

    val sqlTemplateArgs = sqlTemplate.params.map(p => s"${p.name}:${p.typeName}")
    val paramNames = sqlTemplate.params.map(_.name)

    val code =
      s"""package ${packageName}
         |import java.sql.ResultSet
         |
         |object ${name} {
         |  def path : String = "/${packageName.replaceAll("\\.", "/")}/${name}.sql"
         |  def originalSql : String = {
         |    scala.io.Source.fromInputStream(this.getClass.getResourceAsStream(path)).mkString
         |  }
         |  def apply(rs:ResultSet) : ${name} = {
         |    new ${name}(
         |      ${rsReader.mkString(",\n      ")}
         |    )
         |  }
         |  def sql(${sqlTemplateArgs.mkString(", ")}) : String = {
         |    var rendered = originalSql
         |    val params = Seq(${paramNames.map(x => "\"" + x + "\"").mkString(", ")})
         |    val args = Seq(${paramNames.mkString(", ")})
         |    for((p, arg) <- params.zip(args)) {
         |       rendered = rendered.replaceAll("\\\\$$\\\\{" + p + "\\\\}", arg.toString)
         |    }
         |    rendered
         |  }
         |}
         |
         |class ${name}(
         |  ${params.mkString(",\n  ")}
         |) {
         |  def toSeq : Seq[Any] = {
         |    val b = Seq.newBuilder[Any]
         |    ${columnList.map(q => "b += " + q).mkString("\n    ")}
         |    b.result
         |  }
         |  override def toString = toSeq.mkString("\\t")
         |}
         |""".stripMargin

    //info(code)
    code
  }

}
