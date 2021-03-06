/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.csv

import scala.util.control.NonFatal

import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapreduce.TaskAttemptContext

import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.execution.datasources.{CodecStreams, OutputWriter, OutputWriterFactory, PartitionedFile}
import org.apache.spark.sql.types._

object CSVRelation extends Logging {

  def univocityTokenizer(
      file: Dataset[String],
      firstLine: String,
      params: CSVOptions): RDD[Array[String]] = {
    // If header is set, make sure firstLine is materialized before sending to executors.
    val commentPrefix = params.comment.toString
    file.rdd.mapPartitions { iter =>
      val parser = new CsvReader(params)
      val filteredIter = iter.filter { line =>
        line.trim.nonEmpty && !line.startsWith(commentPrefix)
      }
      if (params.headerFlag) {
        filteredIter.filterNot(_ == firstLine).map { item =>
          parser.parseLine(item)
        }
      } else {
        filteredIter.map { item =>
          parser.parseLine(item)
        }
      }
    }
  }

  /**
   * Returns a function that parses a single CSV record (in the form of an array of strings in which
   * each element represents a column) and turns it into either one resulting row or no row (if the
   * the record is malformed).
   *
   * The 2nd argument in the returned function represents the total number of malformed rows
   * observed so far.
   */
  // This is pretty convoluted and we should probably rewrite the entire CSV parsing soon.
  def csvParser(
      schema: StructType,
      requiredColumns: Array[String],
      params: CSVOptions): (Array[String], Int) => Option[InternalRow] = {
    val requiredFields = StructType(requiredColumns.map(schema(_))).fields
    val safeRequiredFields = if (params.dropMalformed) {
      // If `dropMalformed` is enabled, then it needs to parse all the values
      // so that we can decide which row is malformed.
      requiredFields ++ schema.filterNot(requiredFields.contains(_))
    } else {
      requiredFields
    }
    val safeRequiredIndices = new Array[Int](safeRequiredFields.length)
    schema.zipWithIndex.filter { case (field, _) =>
      safeRequiredFields.contains(field)
    }.foreach { case (field, index) =>
      safeRequiredIndices(safeRequiredFields.indexOf(field)) = index
    }
    val requiredSize = requiredFields.length
    val row = new GenericInternalRow(requiredSize)
    val converters = CSVTypeCast.makeConverters(schema, params)

    (tokens: Array[String], numMalformedRows) => {
      if (params.dropMalformed && schema.length != tokens.length) {
        if (numMalformedRows < params.maxMalformedLogPerPartition) {
          logWarning(s"Dropping malformed line: ${tokens.mkString(params.delimiter.toString)}")
        }
        if (numMalformedRows == params.maxMalformedLogPerPartition - 1) {
          logWarning(
            s"More than ${params.maxMalformedLogPerPartition} malformed records have been " +
            "found on this partition. Malformed records from now on will not be logged.")
        }
        None
      } else if (params.failFast && schema.length != tokens.length) {
        throw new RuntimeException(s"Malformed line in FAILFAST mode: " +
          s"${tokens.mkString(params.delimiter.toString)}")
      } else {
        val indexSafeTokens = if (params.permissive && schema.length > tokens.length) {
          tokens ++ new Array[String](schema.length - tokens.length)
        } else if (params.permissive && schema.length < tokens.length) {
          tokens.take(schema.length)
        } else {
          tokens
        }
        try {
          var index: Int = 0
          var subIndex: Int = 0
          while (subIndex < safeRequiredIndices.length) {
            index = safeRequiredIndices(subIndex)
            // It anyway needs to try to parse since it decides if this row is malformed
            // or not after trying to cast in `DROPMALFORMED` mode even if the casted
            // value is not stored in the row.
            val value = converters(index).apply(indexSafeTokens(index))
            if (subIndex < requiredSize) {
              row(subIndex) = value
            }
            subIndex += 1
          }
          Some(row)
        } catch {
          case NonFatal(e) if params.dropMalformed =>
            if (numMalformedRows < params.maxMalformedLogPerPartition) {
              logWarning("Parse exception. " +
                s"Dropping malformed line: ${tokens.mkString(params.delimiter.toString)}")
            }
            if (numMalformedRows == params.maxMalformedLogPerPartition - 1) {
              logWarning(
                s"More than ${params.maxMalformedLogPerPartition} malformed records have been " +
                "found on this partition. Malformed records from now on will not be logged.")
            }
            None
        }
      }
    }
  }

  // Skips the header line of each file if the `header` option is set to true.
  def dropHeaderLine(
      file: PartitionedFile, lines: Iterator[String], csvOptions: CSVOptions): Unit = {
    // TODO What if the first partitioned file consists of only comments and empty lines?
    if (csvOptions.headerFlag && file.start == 0) {
      val nonEmptyLines = if (csvOptions.isCommentSet) {
        val commentPrefix = csvOptions.comment.toString
        lines.dropWhile { line =>
          line.trim.isEmpty || line.trim.startsWith(commentPrefix)
        }
      } else {
        lines.dropWhile(_.trim.isEmpty)
      }

      if (nonEmptyLines.hasNext) nonEmptyLines.drop(1)
    }
  }
}

private[csv] class CSVOutputWriterFactory(params: CSVOptions) extends OutputWriterFactory {
  override def newInstance(
      path: String,
      dataSchema: StructType,
      context: TaskAttemptContext): OutputWriter = {
    new CsvOutputWriter(path, dataSchema, context, params)
  }

  override def getFileExtension(context: TaskAttemptContext): String = {
    ".csv" + CodecStreams.getCompressionExtension(context)
  }
}

private[csv] class CsvOutputWriter(
    path: String,
    dataSchema: StructType,
    context: TaskAttemptContext,
    params: CSVOptions) extends OutputWriter with Logging {

  // A `ValueConverter` is responsible for converting a value of an `InternalRow` to `String`.
  // When the value is null, this converter should not be called.
  private type ValueConverter = (InternalRow, Int) => String

  // `ValueConverter`s for all values in the fields of the schema
  private val valueConverters: Array[ValueConverter] =
    dataSchema.map(_.dataType).map(makeConverter).toArray

  private var printHeader: Boolean = params.headerFlag
  private val writer = CodecStreams.createOutputStream(context, new Path(path))
  private val csvWriter = new LineCsvWriter(params, dataSchema.fieldNames.toSeq, writer)

  private def rowToString(row: InternalRow): Seq[String] = {
    var i = 0
    val values = new Array[String](row.numFields)
    while (i < row.numFields) {
      if (!row.isNullAt(i)) {
        values(i) = valueConverters(i).apply(row, i)
      } else {
        values(i) = params.nullValue
      }
      i += 1
    }
    values
  }

  private def makeConverter(dataType: DataType): ValueConverter = dataType match {
    case DateType =>
      (row: InternalRow, ordinal: Int) =>
        params.dateFormat.format(DateTimeUtils.toJavaDate(row.getInt(ordinal)))

    case TimestampType =>
      (row: InternalRow, ordinal: Int) =>
        params.timestampFormat.format(DateTimeUtils.toJavaTimestamp(row.getLong(ordinal)))

    case udt: UserDefinedType[_] => makeConverter(udt.sqlType)

    case dt: DataType =>
      (row: InternalRow, ordinal: Int) =>
        row.get(ordinal, dt).toString
  }

  override def write(row: InternalRow): Unit = {
    csvWriter.writeRow(rowToString(row), printHeader)
    printHeader = false
  }

  override def close(): Unit = {
    csvWriter.close()
    writer.close()
  }
}
