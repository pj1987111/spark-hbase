/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.hbase

import java.sql.Timestamp

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.hbase._
import org.apache.hadoop.hbase.client.{Admin, Connection, ConnectionFactory, Put, Result}
import org.apache.hadoop.hbase.io.ImmutableBytesWritable
import org.apache.hadoop.hbase.mapreduce.{TableInputFormat, TableOutputFormat}
import org.apache.hadoop.hbase.util.Bytes
import org.apache.hadoop.mapreduce.Job
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SaveMode}
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.{SerializableConfiguration, Utils}
import org.joda.time.DateTime

import scala.collection.mutable.ArrayBuffer

/**
  * Created by allwefantasy on 8/3/2017.
  */
class DefaultSource extends RelationProvider with CreatableRelationProvider with DataSourceRegister {

  override def createRelation(sqlContext: SQLContext, parameters: Map[String, String]): BaseRelation =
    HBaseRelation(parameters, None)(sqlContext)

  override def shortName(): String = "hbase"

  override def createRelation(sqlContext: SQLContext, mode: SaveMode, parameters: Map[String, String], data: DataFrame): BaseRelation = {
    val relation = InsertHBaseRelation(data, parameters)(sqlContext)
    relation.createTableOrAddFamily(parameters.getOrElse("numReg", "3").toInt)
    relation.insert(data, false)
    relation
  }
}

case class InsertHBaseRelation(
                                  dataFrame: DataFrame,
                                  parameters: Map[String, String]
                              )(@transient val sqlContext: SQLContext)
    extends BaseRelation with InsertableRelation with Logging {


  private val wrappedConf = {
    new SerializableConfiguration(HBaseConfBuilder.build(dataFrame.sparkSession, parameters))
  }

  def hbaseConf = wrappedConf.value


  val rowkey = parameters.getOrElse("rowkey", "rowkey")
  val family = parameters.getOrElse("family", "f")
  val outputTableName = parameters("outputTableName")

  def createTableOrAddFamily(numReg: Int, familyName: String = family): Unit = {
    val connection = ConnectionFactory.createConnection(hbaseConf)
    val admin = connection.getAdmin
    if (!admin.isTableAvailable(TableName.valueOf(outputTableName))) {
      createTable(numReg, connection, admin, TableName.valueOf(outputTableName))
    } else if (!isExistColumnFamily(connection, admin, TableName.valueOf(outputTableName), familyName)) {
      appendColumnFamily(connection, admin, outputTableName, familyName)
    }
    admin.close()
    connection.close()
  }

  def isExistColumnFamily(connection: Connection, admin: Admin, tName: TableName, familyName: String): Boolean = {
    var res = false
    if (admin.isTableAvailable(tName)) {
      val table = connection.getTable(tName)
      val tableDescriptor = table.getTableDescriptor
      val columnF = tableDescriptor.getColumnFamilies.find(cf => cf.getNameAsString.equals(familyName))
      res = columnF.nonEmpty
    }
    res
  }

  def appendColumnFamily(connection: Connection, admin: Admin, tableName: String, familyName: String) = {
    val tName = TableName.valueOf(outputTableName)
    val tableDesc = admin.getTableDescriptor(tName)

    val cf = new HColumnDescriptor(Bytes.toBytes(familyName))
    tableDesc.addFamily(cf)
    admin.modifyTable(tName, tableDesc)
  }

  def colFamily(colName: String) = {
    colName.split(":") match {
      case Array(colF, colN) => (colF, colN)
      case Array(colN, _) => (family, colN)
    }
  }

  /**
    * dynamic append column family in table
    *
    * @param data
    */
  def dynamicAppendColumnFamily(data: DataFrame): Unit = {
    val connection = ConnectionFactory.createConnection(hbaseConf)
    val admin = connection.getAdmin
    val familyBuffer = ArrayBuffer[String]()
    data.schema.fieldNames.foreach(fieldName => {
      val cols = fieldName.split(":")
      if (cols.length == 2) {
        familyBuffer.append(cols(0))
      }
    })
    val familyList = familyBuffer.toArray.distinct
    familyList.foreach(colF => {
      if (!isExistColumnFamily(connection, admin, TableName.valueOf(outputTableName), colF)) {
        appendColumnFamily(connection, admin, outputTableName, colF)
      }
    })
    admin.close()
    connection.close()
  }

  def createTable(numReg: Int, connection: Connection, admin: Admin, tName: TableName) {
    if (numReg > 3) {
      if (!admin.isTableAvailable(tName)) {
        val tableDesc = new HTableDescriptor(tName)

        val cf = new HColumnDescriptor(Bytes.toBytes(family))
        tableDesc.addFamily(cf)

        val startKey = Bytes.toBytes("aaaaaaa")
        val endKey = Bytes.toBytes("zzzzzzz")
        val splitKeys = Bytes.split(startKey, endKey, numReg - 3)
        admin.createTable(tableDesc, splitKeys)
        val r = connection.getRegionLocator(tName).getAllRegionLocations
        while (r == null || r.size() == 0) {
          logDebug(s"region not allocated")
          Thread.sleep(1000)
        }
        logInfo(s"region allocated $r")
      }
    } else {
      if (!admin.isTableAvailable(tName)) {
        val tableDesc = new HTableDescriptor(tName)

        val cf = new HColumnDescriptor(Bytes.toBytes(family))
        tableDesc.addFamily(cf)
        admin.createTable(tableDesc)
      }
    }
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    hbaseConf.set(TableOutputFormat.OUTPUT_TABLE, outputTableName)
    val job = Job.getInstance(hbaseConf)
    job.setOutputFormatClass(classOf[TableOutputFormat[String]])
    val jobConfig = job.getConfiguration
    val tempDir = Utils.createTempDir()
    if (jobConfig.get("mapreduce.output.fileoutputformat.outputdir") == null) {
      jobConfig.set("mapreduce.output.fileoutputformat.outputdir", tempDir.getPath + "/outputDataset")
    }

    val fields = data.schema.toArray
    val rowkeyIndex = fields.zipWithIndex.filter(f => f._1.name == rowkey).head._2
    var otherFields = fields.zipWithIndex.filter(f => f._1.name != rowkey)

    val rdd = data.rdd //df.queryExecution.toRdd

    val tsSuffix = parameters.get("tsSuffix") match {
      case Some(tsSuffix) => otherFields = otherFields.filter(f => !f._1.name.endsWith(tsSuffix)); tsSuffix
      case _ => ""
    }

    dynamicAppendColumnFamily(data)

    def convertToPut(row: Row) = {

      val put = new Put(Bytes.toBytes(row.getString(rowkeyIndex)))
      otherFields.foreach { field =>
        if (row.get(field._2) != null) {
          val st = field._1
          val c = st.dataType match {
            case StringType => Bytes.toBytes(row.getString(field._2))
            case FloatType => Bytes.toBytes(row.getFloat(field._2))
            case DoubleType => Bytes.toBytes(row.getDouble(field._2))
            case LongType => Bytes.toBytes(row.getLong(field._2))
            case IntegerType => Bytes.toBytes(row.getInt(field._2))
            case BooleanType => Bytes.toBytes(row.getBoolean(field._2))
            case DateType => Bytes.toBytes(new DateTime(row.getDate(field._2)).getMillis)
            case TimestampType => Bytes.toBytes(new DateTime(row.getTimestamp(field._2)).getMillis)
            case ShortType => Bytes.toBytes(row.getShort(field._2))
            case BinaryType => row.getAs[Array[Byte]](field._2)
            case ByteType => Bytes.toBytes(row.getAs[Byte](field._2))
            //            case ArrayType => Bytes.toBytes(row.getList(field._2).mkString(","))
            //            case DecimalType.BigIntDecimal => Bytes.toBytes(row.getDecimal(field._2))
            case _ => Bytes.toBytes(row.getString(field._2))
          }

          val (colF, colN) = colFamily(field._1.name)
          val tsField = colN + tsSuffix
          if (StringUtils.isNoneBlank(tsSuffix) && row.schema.fieldNames.contains(tsField)) {
            put.addColumn(Bytes.toBytes(colF), Bytes.toBytes(colN), row.getAs[Long](tsField), c)
          } else {
            put.addColumn(Bytes.toBytes(colF), Bytes.toBytes(colN), c)
          }
        }
      }
      (new ImmutableBytesWritable, put)
    }

    rdd.map(convertToPut).saveAsNewAPIHadoopDataset(job.getConfiguration)
  }

  override def schema: StructType = dataFrame.schema
}

case class HBaseRelation(
                            parameters: Map[String, String],
                            userSpecifiedschema: Option[StructType]
                        )(@transient val sqlContext: SQLContext)
    extends BaseRelation with TableScan with Logging {

  private val wrappedConf = {
    val hc = HBaseConfBuilder.build(sqlContext.sparkSession, parameters)
    hc.set(TableInputFormat.INPUT_TABLE, parameters("inputTableName"))
    new SerializableConfiguration(hc)
  }

  def hbaseConf = wrappedConf.value

  def buildScan(): RDD[Row] = {

    val tsSuffix = parameters.getOrElse("tsSuffix", "_ts")
    val family = parameters.getOrElse("family", "")
    val hBaseRDD = sqlContext.sparkContext.newAPIHadoopRDD(hbaseConf, classOf[TableInputFormat], classOf[ImmutableBytesWritable], classOf[Result])
        .map { line =>
          val rowKey = Bytes.toString(line._2.getRow)
          import _root_.net.liftweb.{json => SJSon}
          implicit val formats = SJSon.Serialization.formats(SJSon.NoTypeHints)

          val content = line._2.rawCells().flatMap { cell =>
            val f = new String(CellUtil.cloneFamily(cell))
            if ((StringUtils.isNotBlank(family) && family.equals(f))
                || StringUtils.isBlank(family)) {
              val columnName = new String(CellUtil.cloneQualifier(cell))
              val fullColumnName = f + ":" + columnName
              parameters.get("field.type." + columnName) match {
                case Some(i) =>
                  val value = i match {
                    case "LongType" => Bytes.toLong(CellUtil.cloneValue(cell))
                    case "FloatType" => Bytes.toFloat(CellUtil.cloneValue(cell))
                    case "DoubleType" => Bytes.toDouble(CellUtil.cloneValue(cell))
                    case "IntegerType" => Bytes.toInt(CellUtil.cloneValue(cell))
                    case "BooleanType" => Bytes.toBoolean(CellUtil.cloneValue(cell))
                    case "BinaryType" => CellUtil.cloneValue(cell)
                    case "TimestampType" => new Timestamp(Bytes.toLong(CellUtil.cloneValue(cell)))
                    case "DateType" => new java.sql.Date(Bytes.toLong(CellUtil.cloneValue(cell)))
                    case "ShortType" => Bytes.toShort(CellUtil.cloneValue(cell))
                    case "ByteType" => CellUtil.cloneValue(cell).head
                    case _ => Bytes.toString(CellUtil.cloneValue(cell))
                  }
                  (fullColumnName, value) :: (fullColumnName + tsSuffix, cell.getTimestamp) :: Nil
                case None => (fullColumnName, Bytes.toString(CellUtil.cloneValue(cell))) :: (fullColumnName + tsSuffix, cell.getTimestamp) :: Nil
              }
            } else {
              Nil
            }
          }.toMap

          if (content.size > 0) {
            val contentStr = SJSon.Serialization.write(content)

            Row.fromSeq(Seq(UTF8String.fromString(rowKey), UTF8String.fromString(contentStr)))
          } else {
            null
          }
        }.filter(_ != null)
    hBaseRDD
  }


  override def schema: StructType = {
    import org.apache.spark.sql.types._
    StructType(
      Array(
        StructField("rowkey", StringType, false),
        StructField("content", StringType)
      )
    )

  }
}

object HBaseParams {
  val ROW_KEY = "rowkey"
}

