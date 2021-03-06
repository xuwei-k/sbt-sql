sbt-sql 
====

A sbt plugin for generating model classes from SQL query files in `src/main/sql`.

## Why you need sbt-sql?

 - Integrate the power of SQL and Scala
     - If you write an SQL, it creates a Scala class to read the SQL result.
 - Type safety
     - No longer need to write a code like `ResultSet.getColumn("id")` etc. 
     - Editors such as IntelliJ can show the SQL result parameter names and types.
 - Reuse your SQL as a template
     - You can embed parameters in your SQL with automatically generated Scala functions.

## Usage

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sql/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sql)

**project/plugins.sbt**
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sql" % "(version)")
```

**build.sbt**

This is an example of using Presto JDBC driver:
```scala
// Add your JDBC driver to the dependency
libraryDependencies += Seq(
  // For using presto-jdbc
  "com.facebook.presto" % "presto-jdbc" % "0.163"
)

// Configure yoru JDBC driver
sqlDir := (sourceDirectory in Compile).value / "sql"
jdbcDriver := "com.facebook.presto.jdbc.PrestoDriver"
jdbcURL := "(jdbc url e.g., jdbc:presto://.... )"
jdbcUser := "(jdbc user name)"
jdbcPassword := "(jdbc password)"
```

### Using Presto
For using Presto JDBC, import `prestoSettings`:
```scala
SQL.prestoSettings
jdbcURL := "jdbc:presto://api-presto.treasuredata.com:443/td-presto"
jdbcUser := "presto user name"
```

### Using Treasure Data Presto

To use [Treasure Data](http://www.treasuredata.com/) Presto, import tdPrestoSettings and
set TD_API_KEY environment variable:
```
SQL.tdPrestoSettings
```
or add your TD API KEY to the sbt credential:
`$HOME/.sbt/0.13/td.sbt`
```
credentials += Credentials("Treasure Data",
        "api-presto.treasuredata.com",
        "(your TD API KEY)",
        "")
```

## Writing SQL

**src/main/sql/presto/sample/nasdaq.sql**
```sql
select * from sample_datasets.nasdaq
where TD_TIME_RANGE(time, '${start:String}', '${end:String}')
```

From this SQL file, sbt-sql generates Scala model classes and utility methods.

* SQL can contain variables `${(variable name):(type)}`, and sbt-sql generates a function to populate them, such as `Nasdaq.sql(start, end)`. So the SQL file with template variables can be called as if it were a function in Scala.


### Generated Files 
**target/src_managed/main/sample/Nasdaq.scala**
```scala
package sample
import java.sql.ResultSet

object nasdaq {
  def path : String = "/sample/nasdaq.sql"
  def originalSql : String = {
    scala.io.Source.fromInputStream(this.getClass.getResourceAsStream(path)).mkString
  }
  def apply(rs:ResultSet) : nasdaq = {
    new nasdaq(
      rs.getString(1),
      rs.getDouble(2),
      rs.getLong(3),
      rs.getDouble(4),
      rs.getDouble(5),
      rs.getDouble(6),
      rs.getLong(7)
    )
  }
  def sql() : String = {
    var rendered = originalSql
    val params = Seq()
    val args = Seq()
    for((p, arg) <- params.zip(args)) {
       rendered = rendered.replaceAll("\\$\\{" + p + "\\}", arg.toString)
    }
    rendered
  }

  def select()(implicit conn:java.sql.Connection) : Seq[nasdaq] = {
    val query = sql()
    selectWith(query)
  }

  def selectWith(sql:String)(implicit conn:java.sql.Connection) : Seq[nasdaq] = {
    val stmt = conn.createStatement()
    try {
      val rs = stmt.executeQuery(sql)
      try {
        val b = Seq.newBuilder[nasdaq]
        while(rs.next) {
          b += nasdaq(rs)
        }
        b.result
      }
      finally {
        rs.close()
      }
    }
    finally {
      stmt.close()
    }
  }
}

class nasdaq(
  val symbol:String,
  val open:Double,
  val volume:Long,
  val high:Double,
  val low:Double,
  val close:Double,
  val time:Long
) {
  def toSeq : Seq[Any] = {
    val b = Seq.newBuilder[Any]
    b += symbol
    b += open
    b += volume
    b += high
    b += low
    b += close
    b += time
    b.result
  }
  override def toString = toSeq.mkString("\t")
}
``` 
 
