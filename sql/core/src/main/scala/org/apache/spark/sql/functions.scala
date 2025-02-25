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

package org.apache.spark.sql

import scala.language.implicitConversions
import scala.reflect.runtime.universe.{TypeTag, typeTag}
import scala.util.Try

import org.apache.spark.annotation.Experimental
import org.apache.spark.sql.catalyst.{SqlParser, ScalaReflection}
import org.apache.spark.sql.catalyst.analysis.{UnresolvedFunction, Star}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical.BroadcastHint
import org.apache.spark.sql.types._
import org.apache.spark.util.Utils

/**
 * :: Experimental ::
 * Functions available for [[DataFrame]].
 *
 * @groupname udf_funcs UDF functions
 * @groupname agg_funcs Aggregate functions
 * @groupname datetime_funcs Date time functions
 * @groupname sort_funcs Sorting functions
 * @groupname normal_funcs Non-aggregate functions
 * @groupname math_funcs Math functions
 * @groupname misc_funcs Misc functions
 * @groupname window_funcs Window functions
 * @groupname string_funcs String functions
 * @groupname collection_funcs Collection functions
 * @groupname Ungrouped Support functions for DataFrames.
 * @since 1.3.0
 */
@Experimental
// scalastyle:off
object functions {
// scalastyle:on

  private[this] implicit def toColumn(expr: Expression): Column = Column(expr)

  /**
   * Returns a [[Column]] based on the given column name.
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def col(colName: String): Column = Column(colName)

  /**
   * Returns a [[Column]] based on the given column name. Alias of [[col]].
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def column(colName: String): Column = Column(colName)

  /**
   * Convert a number in string format from one base to another.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def conv(num: Column, fromBase: Int, toBase: Int): Column =
    Conv(num.expr, lit(fromBase).expr, lit(toBase).expr)

  /**
   * Creates a [[Column]] of literal value.
   *
   * The passed in object is returned directly if it is already a [[Column]].
   * If the object is a Scala Symbol, it is converted into a [[Column]] also.
   * Otherwise, a new [[Column]] is created to represent the literal value.
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def lit(literal: Any): Column = {
    literal match {
      case c: Column => return c
      case s: Symbol => return new ColumnName(literal.asInstanceOf[Symbol].name)
      case _ =>  // continue
    }

    val literalExpr = Literal(literal)
    Column(literalExpr)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Sort functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Returns a sort expression based on ascending order of the column.
   * {{{
   *   // Sort by dept in ascending order, and then age in descending order.
   *   df.sort(asc("dept"), desc("age"))
   * }}}
   *
   * @group sort_funcs
   * @since 1.3.0
   */
  def asc(columnName: String): Column = Column(columnName).asc

  /**
   * Returns a sort expression based on the descending order of the column.
   * {{{
   *   // Sort by dept in ascending order, and then age in descending order.
   *   df.sort(asc("dept"), desc("age"))
   * }}}
   *
   * @group sort_funcs
   * @since 1.3.0
   */
  def desc(columnName: String): Column = Column(columnName).desc

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Aggregate functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Aggregate function: returns the sum of all values in the expression.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def sum(e: Column): Column = Sum(e.expr)

  /**
   * Aggregate function: returns the sum of all values in the given column.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def sum(columnName: String): Column = sum(Column(columnName))

  /**
   * Aggregate function: returns the sum of distinct values in the expression.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def sumDistinct(e: Column): Column = SumDistinct(e.expr)

  /**
   * Aggregate function: returns the sum of distinct values in the expression.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def sumDistinct(columnName: String): Column = sumDistinct(Column(columnName))

  /**
   * Aggregate function: returns the number of items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def count(e: Column): Column = e.expr match {
    // Turn count(*) into count(1)
    case s: Star => Count(Literal(1))
    case _ => Count(e.expr)
  }

  /**
   * Aggregate function: returns the number of items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def count(columnName: String): Column = count(Column(columnName))

  /**
   * Aggregate function: returns the number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def countDistinct(expr: Column, exprs: Column*): Column =
    CountDistinct((expr +: exprs).map(_.expr))

  /**
   * Aggregate function: returns the number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def countDistinct(columnName: String, columnNames: String*): Column =
    countDistinct(Column(columnName), columnNames.map(Column.apply) : _*)

  /**
   * Aggregate function: returns the approximate number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def approxCountDistinct(e: Column): Column = ApproxCountDistinct(e.expr)

  /**
   * Aggregate function: returns the approximate number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def approxCountDistinct(columnName: String): Column = approxCountDistinct(column(columnName))

  /**
   * Aggregate function: returns the approximate number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def approxCountDistinct(e: Column, rsd: Double): Column = ApproxCountDistinct(e.expr, rsd)

  /**
   * Aggregate function: returns the approximate number of distinct items in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def approxCountDistinct(columnName: String, rsd: Double): Column = {
    approxCountDistinct(Column(columnName), rsd)
  }

  /**
   * Aggregate function: returns the average of the values in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def avg(e: Column): Column = Average(e.expr)

  /**
   * Aggregate function: returns the average of the values in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def avg(columnName: String): Column = avg(Column(columnName))

  /**
   * Aggregate function: returns the first value in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def first(e: Column): Column = First(e.expr)

  /**
   * Aggregate function: returns the first value of a column in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def first(columnName: String): Column = first(Column(columnName))

  /**
   * Aggregate function: returns the last value in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def last(e: Column): Column = Last(e.expr)

  /**
   * Aggregate function: returns the last value of the column in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def last(columnName: String): Column = last(Column(columnName))

  /**
   * Aggregate function: returns the average of the values in a group.
   * Alias for avg.
   *
   * @group agg_funcs
   * @since 1.4.0
   */
  def mean(e: Column): Column = avg(e)

  /**
   * Aggregate function: returns the average of the values in a group.
   * Alias for avg.
   *
   * @group agg_funcs
   * @since 1.4.0
   */
  def mean(columnName: String): Column = avg(columnName)

  /**
   * Aggregate function: returns the minimum value of the expression in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def min(e: Column): Column = Min(e.expr)

  /**
   * Aggregate function: returns the minimum value of the column in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def min(columnName: String): Column = min(Column(columnName))

  /**
   * Aggregate function: returns the maximum value of the expression in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def max(e: Column): Column = Max(e.expr)

  /**
   * Aggregate function: returns the maximum value of the column in a group.
   *
   * @group agg_funcs
   * @since 1.3.0
   */
  def max(columnName: String): Column = max(Column(columnName))

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Window functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Window function: returns the value that is `offset` rows before the current row, and
   * `null` if there is less than `offset` rows before the current row. For example,
   * an `offset` of one will return the previous row at any given point in the window partition.
   *
   * This is equivalent to the LAG function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lag(e: Column, offset: Int): Column = {
    lag(e, offset, null)
  }

  /**
   * Window function: returns the value that is `offset` rows before the current row, and
   * `null` if there is less than `offset` rows before the current row. For example,
   * an `offset` of one will return the previous row at any given point in the window partition.
   *
   * This is equivalent to the LAG function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lag(columnName: String, offset: Int): Column = {
    lag(columnName, offset, null)
  }

  /**
   * Window function: returns the value that is `offset` rows before the current row, and
   * `defaultValue` if there is less than `offset` rows before the current row. For example,
   * an `offset` of one will return the previous row at any given point in the window partition.
   *
   * This is equivalent to the LAG function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lag(columnName: String, offset: Int, defaultValue: Any): Column = {
    lag(Column(columnName), offset, defaultValue)
  }

  /**
   * Window function: returns the value that is `offset` rows before the current row, and
   * `defaultValue` if there is less than `offset` rows before the current row. For example,
   * an `offset` of one will return the previous row at any given point in the window partition.
   *
   * This is equivalent to the LAG function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lag(e: Column, offset: Int, defaultValue: Any): Column = {
    UnresolvedWindowFunction("lag", e.expr :: Literal(offset) :: Literal(defaultValue) :: Nil)
  }

  /**
   * Window function: returns the value that is `offset` rows after the current row, and
   * `null` if there is less than `offset` rows after the current row. For example,
   * an `offset` of one will return the next row at any given point in the window partition.
   *
   * This is equivalent to the LEAD function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lead(columnName: String, offset: Int): Column = {
    lead(columnName, offset, null)
  }

  /**
   * Window function: returns the value that is `offset` rows after the current row, and
   * `null` if there is less than `offset` rows after the current row. For example,
   * an `offset` of one will return the next row at any given point in the window partition.
   *
   * This is equivalent to the LEAD function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lead(e: Column, offset: Int): Column = {
    lead(e, offset, null)
  }

  /**
   * Window function: returns the value that is `offset` rows after the current row, and
   * `defaultValue` if there is less than `offset` rows after the current row. For example,
   * an `offset` of one will return the next row at any given point in the window partition.
   *
   * This is equivalent to the LEAD function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lead(columnName: String, offset: Int, defaultValue: Any): Column = {
    lead(Column(columnName), offset, defaultValue)
  }

  /**
   * Window function: returns the value that is `offset` rows after the current row, and
   * `defaultValue` if there is less than `offset` rows after the current row. For example,
   * an `offset` of one will return the next row at any given point in the window partition.
   *
   * This is equivalent to the LEAD function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def lead(e: Column, offset: Int, defaultValue: Any): Column = {
    UnresolvedWindowFunction("lead", e.expr :: Literal(offset) :: Literal(defaultValue) :: Nil)
  }

  /**
   * Window function: returns the ntile group id (from 1 to `n` inclusive) in an ordered window
   * partition. Fow example, if `n` is 4, the first quarter of the rows will get value 1, the second
   * quarter will get 2, the third quarter will get 3, and the last quarter will get 4.
   *
   * This is equivalent to the NTILE function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def ntile(n: Int): Column = {
    UnresolvedWindowFunction("ntile", lit(n).expr :: Nil)
  }

  /**
   * Window function: returns a sequential number starting at 1 within a window partition.
   *
   * This is equivalent to the ROW_NUMBER function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def rowNumber(): Column = {
    UnresolvedWindowFunction("row_number", Nil)
  }

  /**
   * Window function: returns the rank of rows within a window partition, without any gaps.
   *
   * The difference between rank and denseRank is that denseRank leaves no gaps in ranking
   * sequence when there are ties. That is, if you were ranking a competition using denseRank
   * and had three people tie for second place, you would say that all three were in second
   * place and that the next person came in third.
   *
   * This is equivalent to the DENSE_RANK function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def denseRank(): Column = {
    UnresolvedWindowFunction("dense_rank", Nil)
  }

  /**
   * Window function: returns the rank of rows within a window partition.
   *
   * The difference between rank and denseRank is that denseRank leaves no gaps in ranking
   * sequence when there are ties. That is, if you were ranking a competition using denseRank
   * and had three people tie for second place, you would say that all three were in second
   * place and that the next person came in third.
   *
   * This is equivalent to the RANK function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def rank(): Column = {
    UnresolvedWindowFunction("rank", Nil)
  }

  /**
   * Window function: returns the cumulative distribution of values within a window partition,
   * i.e. the fraction of rows that are below the current row.
   *
   * {{{
   *   N = total number of rows in the partition
   *   cumeDist(x) = number of values before (and including) x / N
   * }}}
   *
   *
   * This is equivalent to the CUME_DIST function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def cumeDist(): Column = {
    UnresolvedWindowFunction("cume_dist", Nil)
  }

  /**
   * Window function: returns the relative rank (i.e. percentile) of rows within a window partition.
   *
   * This is computed by:
   * {{{
   *   (rank of row in its partition - 1) / (number of rows in the partition - 1)
   * }}}
   *
   * This is equivalent to the PERCENT_RANK function in SQL.
   *
   * @group window_funcs
   * @since 1.4.0
   */
  def percentRank(): Column = {
    UnresolvedWindowFunction("percent_rank", Nil)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Non-aggregate functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Computes the absolute value.
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def abs(e: Column): Column = Abs(e.expr)

  /**
   * Creates a new array column. The input columns must all have the same data type.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  @scala.annotation.varargs
  def array(cols: Column*): Column = CreateArray(cols.map(_.expr))

  /**
   * Creates a new array column. The input columns must all have the same data type.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def array(colName: String, colNames: String*): Column = {
    array((colName +: colNames).map(col) : _*)
  }

  /**
   * Marks a DataFrame as small enough for use in broadcast joins.
   *
   * The following example marks the right DataFrame for broadcast hash join using `joinKey`.
   * {{{
   *   // left and right are DataFrames
   *   left.join(broadcast(right), "joinKey")
   * }}}
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  def broadcast(df: DataFrame): DataFrame = {
    DataFrame(df.sqlContext, BroadcastHint(df.logicalPlan))
  }

  /**
   * Returns the first column that is not null and not NaN.
   * {{{
   *   df.select(coalesce(df("a"), df("b")))
   * }}}
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  @scala.annotation.varargs
  def coalesce(e: Column*): Column = Coalesce(e.map(_.expr))

  /**
   * Creates a new row for each element in the given array or map column.
   */
  def explode(e: Column): Column = Explode(e.expr)

  /**
   * Return true iff the column is NaN.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  def isNaN(e: Column): Column = IsNaN(e.expr)

  /**
   * A column expression that generates monotonically increasing 64-bit integers.
   *
   * The generated ID is guaranteed to be monotonically increasing and unique, but not consecutive.
   * The current implementation puts the partition ID in the upper 31 bits, and the record number
   * within each partition in the lower 33 bits. The assumption is that the data frame has
   * less than 1 billion partitions, and each partition has less than 8 billion records.
   *
   * As an example, consider a [[DataFrame]] with two partitions, each with 3 records.
   * This expression would return the following IDs:
   * 0, 1, 2, 8589934592 (1L << 33), 8589934593, 8589934594.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def monotonicallyIncreasingId(): Column = MonotonicallyIncreasingID()

  /**
   * Return an alternative value `r` if `l` is NaN.
   * This function is useful for mapping NaN values to null.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  def nanvl(l: Column, r: Column): Column = NaNvl(l.expr, r.expr)

  /**
   * Unary minus, i.e. negate the expression.
   * {{{
   *   // Select the amount column and negates all values.
   *   // Scala:
   *   df.select( -df("amount") )
   *
   *   // Java:
   *   df.select( negate(df.col("amount")) );
   * }}}
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def negate(e: Column): Column = -e

  /**
   * Inversion of boolean expression, i.e. NOT.
   * {{{
   *   // Scala: select rows that are not active (isActive === false)
   *   df.filter( !df("isActive") )
   *
   *   // Java:
   *   df.filter( not(df.col("isActive")) );
   * }}}
   *
   * @group normal_funcs
   * @since 1.3.0
   */
  def not(e: Column): Column = !e

  /**
   * Evaluates a list of conditions and returns one of multiple possible result expressions.
   * If otherwise is not defined at the end, null is returned for unmatched conditions.
   *
   * {{{
   *   // Example: encoding gender string column into integer.
   *
   *   // Scala:
   *   people.select(when(people("gender") === "male", 0)
   *     .when(people("gender") === "female", 1)
   *     .otherwise(2))
   *
   *   // Java:
   *   people.select(when(col("gender").equalTo("male"), 0)
   *     .when(col("gender").equalTo("female"), 1)
   *     .otherwise(2))
   * }}}
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def when(condition: Column, value: Any): Column = {
    CaseWhen(Seq(condition.expr, lit(value).expr))
  }

  /**
   * Generate a random column with i.i.d. samples from U[0.0, 1.0].
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def rand(seed: Long): Column = Rand(seed)

  /**
   * Generate a random column with i.i.d. samples from U[0.0, 1.0].
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def rand(): Column = rand(Utils.random.nextLong)

  /**
   * Generate a column with i.i.d. samples from the standard normal distribution.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def randn(seed: Long): Column = Randn(seed)

  /**
   * Generate a column with i.i.d. samples from the standard normal distribution.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def randn(): Column = randn(Utils.random.nextLong)

  /**
   * Partition ID of the Spark task.
   *
   * Note that this is indeterministic because it depends on data partitioning and task scheduling.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def sparkPartitionId(): Column = SparkPartitionID()

  /**
   * The file name of the current Spark task
   *
   * Note that this is indeterministic becuase it depends on what is currently being read in.
   *
   * @group normal_funcs
   */
  def inputFileName(): Column = InputFileName()

  /**
   * Computes the square root of the specified float value.
   *
   * @group math_funcs
   * @since 1.3.0
   */
  def sqrt(e: Column): Column = Sqrt(e.expr)

  /**
   * Computes the square root of the specified float value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def sqrt(colName: String): Column = sqrt(Column(colName))

  /**
   * Creates a new struct column.
   * If the input column is a column in a [[DataFrame]], or a derived column expression
   * that is named (i.e. aliased), its name would be remained as the StructField's name,
   * otherwise, the newly generated StructField's name would be auto generated as col${index + 1},
   * i.e. col1, col2, col3, ...
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  @scala.annotation.varargs
  def struct(cols: Column*): Column = {
    CreateStruct(cols.map(_.expr))
  }

  /**
   * Creates a new struct column that composes multiple input columns.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def struct(colName: String, colNames: String*): Column = {
    struct((colName +: colNames).map(col) : _*)
  }

  /**
   * Computes bitwise NOT.
   *
   * @group normal_funcs
   * @since 1.4.0
   */
  def bitwiseNOT(e: Column): Column = BitwiseNot(e.expr)

  /**
   * Parses the expression string into the column that it represents, similar to
   * DataFrame.selectExpr
   * {{{
   *   // get the number of words of each length
   *   df.groupBy(expr("length(word)")).count()
   * }}}
   *
   * @group normal_funcs
   */
  def expr(expr: String): Column = Column(new SqlParser().parseExpression(expr))

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Math Functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Computes the cosine inverse of the given value; the returned angle is in the range
   * 0.0 through pi.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def acos(e: Column): Column = Acos(e.expr)

  /**
   * Computes the cosine inverse of the given column; the returned angle is in the range
   * 0.0 through pi.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def acos(columnName: String): Column = acos(Column(columnName))

  /**
   * Computes the sine inverse of the given value; the returned angle is in the range
   * -pi/2 through pi/2.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def asin(e: Column): Column = Asin(e.expr)

  /**
   * Computes the sine inverse of the given column; the returned angle is in the range
   * -pi/2 through pi/2.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def asin(columnName: String): Column = asin(Column(columnName))

  /**
   * Computes the tangent inverse of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan(e: Column): Column = Atan(e.expr)

  /**
   * Computes the tangent inverse of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan(columnName: String): Column = atan(Column(columnName))

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(l: Column, r: Column): Column = Atan2(l.expr, r.expr)

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(l: Column, rightName: String): Column = atan2(l, Column(rightName))

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(leftName: String, r: Column): Column = atan2(Column(leftName), r)

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(leftName: String, rightName: String): Column =
    atan2(Column(leftName), Column(rightName))

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(l: Column, r: Double): Column = atan2(l, lit(r).expr)

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(leftName: String, r: Double): Column = atan2(Column(leftName), r)

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(l: Double, r: Column): Column = atan2(lit(l).expr, r)

  /**
   * Returns the angle theta from the conversion of rectangular coordinates (x, y) to
   * polar coordinates (r, theta).
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def atan2(l: Double, rightName: String): Column = atan2(l, Column(rightName))

  /**
   * An expression that returns the string representation of the binary value of the given long
   * column. For example, bin("12") returns "1100".
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def bin(e: Column): Column = Bin(e.expr)

  /**
   * An expression that returns the string representation of the binary value of the given long
   * column. For example, bin("12") returns "1100".
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def bin(columnName: String): Column = bin(Column(columnName))

  /**
   * Computes the cube-root of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cbrt(e: Column): Column = Cbrt(e.expr)

  /**
   * Computes the cube-root of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cbrt(columnName: String): Column = cbrt(Column(columnName))

  /**
   * Computes the ceiling of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def ceil(e: Column): Column = Ceil(e.expr)

  /**
   * Computes the ceiling of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def ceil(columnName: String): Column = ceil(Column(columnName))

  /**
   * Computes the cosine of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cos(e: Column): Column = Cos(e.expr)

  /**
   * Computes the cosine of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cos(columnName: String): Column = cos(Column(columnName))

  /**
   * Computes the hyperbolic cosine of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cosh(e: Column): Column = Cosh(e.expr)

  /**
   * Computes the hyperbolic cosine of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def cosh(columnName: String): Column = cosh(Column(columnName))

  /**
   * Returns the current date.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def current_date(): Column = CurrentDate()

  /**
   * Returns the current timestamp.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def current_timestamp(): Column = CurrentTimestamp()

  /**
   * Computes the exponential of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def exp(e: Column): Column = Exp(e.expr)

  /**
   * Computes the exponential of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def exp(columnName: String): Column = exp(Column(columnName))

  /**
   * Computes the exponential of the given value minus one.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def expm1(e: Column): Column = Expm1(e.expr)

  /**
   * Computes the exponential of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def expm1(columnName: String): Column = expm1(Column(columnName))

  /**
   * Computes the factorial of the given value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def factorial(e: Column): Column = Factorial(e.expr)

  /**
   * Computes the factorial of the given column.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def factorial(columnName: String): Column = factorial(Column(columnName))

  /**
   * Computes the floor of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def floor(e: Column): Column = Floor(e.expr)

  /**
   * Computes the floor of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def floor(columnName: String): Column = floor(Column(columnName))

  /**
   * Returns the greatest value of the list of values, skipping null values.
   * This function takes at least 2 parameters. It will return null iff all parameters are null.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def greatest(exprs: Column*): Column = {
    require(exprs.length > 1, "greatest requires at least 2 arguments.")
    Greatest(exprs.map(_.expr))
  }

  /**
   * Returns the greatest value of the list of column names, skipping null values.
   * This function takes at least 2 parameters. It will return null iff all parameters are null.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def greatest(columnName: String, columnNames: String*): Column = {
    greatest((columnName +: columnNames).map(Column.apply): _*)
  }

  /**
    * Computes hex value of the given column.
    *
    * @group math_funcs
    * @since 1.5.0
    */
  def hex(column: Column): Column = Hex(column.expr)

  /**
   * Inverse of hex. Interprets each pair of characters as a hexadecimal number
   * and converts to the byte representation of number.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def unhex(column: Column): Column = Unhex(column.expr)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(l: Column, r: Column): Column = Hypot(l.expr, r.expr)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(l: Column, rightName: String): Column = hypot(l, Column(rightName))

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(leftName: String, r: Column): Column = hypot(Column(leftName), r)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(leftName: String, rightName: String): Column =
    hypot(Column(leftName), Column(rightName))

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(l: Column, r: Double): Column = hypot(l, lit(r).expr)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(leftName: String, r: Double): Column = hypot(Column(leftName), r)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(l: Double, r: Column): Column = hypot(lit(l).expr, r)

  /**
   * Computes `sqrt(a^2^ + b^2^)` without intermediate overflow or underflow.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def hypot(l: Double, rightName: String): Column = hypot(l, Column(rightName))

  /**
   * Returns the least value of the list of values, skipping null values.
   * This function takes at least 2 parameters. It will return null iff all parameters are null.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def least(exprs: Column*): Column = {
    require(exprs.length > 1, "least requires at least 2 arguments.")
    Least(exprs.map(_.expr))
  }

  /**
   * Returns the least value of the list of column names, skipping null values.
   * This function takes at least 2 parameters. It will return null iff all parameters are null.
   *
   * @group normal_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def least(columnName: String, columnNames: String*): Column = {
    least((columnName +: columnNames).map(Column.apply): _*)
  }

  /**
   * Computes the natural logarithm of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log(e: Column): Column = Log(e.expr)

  /**
   * Computes the natural logarithm of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log(columnName: String): Column = log(Column(columnName))

  /**
   * Returns the first argument-base logarithm of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log(base: Double, a: Column): Column = Logarithm(lit(base).expr, a.expr)

  /**
   * Returns the first argument-base logarithm of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log(base: Double, columnName: String): Column = log(base, Column(columnName))

  /**
   * Computes the logarithm of the given value in base 10.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log10(e: Column): Column = Log10(e.expr)

  /**
   * Computes the logarithm of the given value in base 10.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log10(columnName: String): Column = log10(Column(columnName))

  /**
   * Computes the natural logarithm of the given value plus one.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log1p(e: Column): Column = Log1p(e.expr)

  /**
   * Computes the natural logarithm of the given column plus one.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def log1p(columnName: String): Column = log1p(Column(columnName))

  /**
   * Computes the logarithm of the given column in base 2.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def log2(expr: Column): Column = Log2(expr.expr)

  /**
   * Computes the logarithm of the given value in base 2.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def log2(columnName: String): Column = log2(Column(columnName))

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(l: Column, r: Column): Column = Pow(l.expr, r.expr)

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(l: Column, rightName: String): Column = pow(l, Column(rightName))

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(leftName: String, r: Column): Column = pow(Column(leftName), r)

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(leftName: String, rightName: String): Column = pow(Column(leftName), Column(rightName))

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(l: Column, r: Double): Column = pow(l, lit(r).expr)

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(leftName: String, r: Double): Column = pow(Column(leftName), r)

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(l: Double, r: Column): Column = pow(lit(l).expr, r)

  /**
   * Returns the value of the first argument raised to the power of the second argument.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def pow(l: Double, rightName: String): Column = pow(l, Column(rightName))

  /**
   * Returns the positive value of dividend mod divisor.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def pmod(dividend: Column, divisor: Column): Column = Pmod(dividend.expr, divisor.expr)

  /**
   * Returns the positive value of dividend mod divisor.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def pmod(dividendColName: String, divisorColName: String): Column =
    pmod(Column(dividendColName), Column(divisorColName))

  /**
   * Returns the double value that is closest in value to the argument and
   * is equal to a mathematical integer.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def rint(e: Column): Column = Rint(e.expr)

  /**
   * Returns the double value that is closest in value to the argument and
   * is equal to a mathematical integer.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def rint(columnName: String): Column = rint(Column(columnName))

  /**
   * Returns the value of the column `e` rounded to 0 decimal places.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def round(e: Column): Column = round(e.expr, 0)

  /**
   * Returns the value of the given column rounded to 0 decimal places.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def round(columnName: String): Column = round(Column(columnName), 0)

  /**
   * Round the value of `e` to `scale` decimal places if `scale` >= 0
   * or at integral part when `scale` < 0.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def round(e: Column, scale: Int): Column = Round(e.expr, Literal(scale))

  /**
   * Round the value of the given column to `scale` decimal places if `scale` >= 0
   * or at integral part when `scale` < 0.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def round(columnName: String, scale: Int): Column = round(Column(columnName), scale)

  /**
   * Shift the the given value numBits left. If the given value is a long value, this function
   * will return a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftLeft(e: Column, numBits: Int): Column = ShiftLeft(e.expr, lit(numBits).expr)

  /**
   * Shift the the given value numBits left. If the given value is a long value, this function
   * will return a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftLeft(columnName: String, numBits: Int): Column =
    shiftLeft(Column(columnName), numBits)

  /**
   * Shift the the given value numBits right. If the given value is a long value, it will return
   * a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftRight(e: Column, numBits: Int): Column = ShiftRight(e.expr, lit(numBits).expr)

  /**
   * Unsigned shift the the given value numBits right. If the given value is a long value,
   * it will return a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftRightUnsigned(columnName: String, numBits: Int): Column =
    shiftRightUnsigned(Column(columnName), numBits)

  /**
   * Unsigned shift the the given value numBits right. If the given value is a long value,
   * it will return a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftRightUnsigned(e: Column, numBits: Int): Column =
    ShiftRightUnsigned(e.expr, lit(numBits).expr)

  /**
   * Shift the the given value numBits right. If the given value is a long value, it will return
   * a long value else it will return an integer value.
   *
   * @group math_funcs
   * @since 1.5.0
   */
  def shiftRight(columnName: String, numBits: Int): Column =
    shiftRight(Column(columnName), numBits)

  /**
   * Computes the signum of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def signum(e: Column): Column = Signum(e.expr)

  /**
   * Computes the signum of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def signum(columnName: String): Column = signum(Column(columnName))

  /**
   * Computes the sine of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def sin(e: Column): Column = Sin(e.expr)

  /**
   * Computes the sine of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def sin(columnName: String): Column = sin(Column(columnName))

  /**
   * Computes the hyperbolic sine of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def sinh(e: Column): Column = Sinh(e.expr)

  /**
   * Computes the hyperbolic sine of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def sinh(columnName: String): Column = sinh(Column(columnName))

  /**
   * Computes the tangent of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def tan(e: Column): Column = Tan(e.expr)

  /**
   * Computes the tangent of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def tan(columnName: String): Column = tan(Column(columnName))

  /**
   * Computes the hyperbolic tangent of the given value.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def tanh(e: Column): Column = Tanh(e.expr)

  /**
   * Computes the hyperbolic tangent of the given column.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def tanh(columnName: String): Column = tanh(Column(columnName))

  /**
   * Converts an angle measured in radians to an approximately equivalent angle measured in degrees.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def toDegrees(e: Column): Column = ToDegrees(e.expr)

  /**
   * Converts an angle measured in radians to an approximately equivalent angle measured in degrees.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def toDegrees(columnName: String): Column = toDegrees(Column(columnName))

  /**
   * Converts an angle measured in degrees to an approximately equivalent angle measured in radians.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def toRadians(e: Column): Column = ToRadians(e.expr)

  /**
   * Converts an angle measured in degrees to an approximately equivalent angle measured in radians.
   *
   * @group math_funcs
   * @since 1.4.0
   */
  def toRadians(columnName: String): Column = toRadians(Column(columnName))

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Misc functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Calculates the MD5 digest of a binary column and returns the value
   * as a 32 character hex string.
   *
   * @group misc_funcs
   * @since 1.5.0
   */
  def md5(e: Column): Column = Md5(e.expr)

  /**
   * Calculates the SHA-1 digest of a binary column and returns the value
   * as a 40 character hex string.
   *
   * @group misc_funcs
   * @since 1.5.0
   */
  def sha1(e: Column): Column = Sha1(e.expr)

  /**
   * Calculates the SHA-2 family of hash functions of a binary column and
   * returns the value as a hex string.
   *
   * @param e column to compute SHA-2 on.
   * @param numBits one of 224, 256, 384, or 512.
   *
   * @group misc_funcs
   * @since 1.5.0
   */
  def sha2(e: Column, numBits: Int): Column = {
    require(Seq(0, 224, 256, 384, 512).contains(numBits),
      s"numBits $numBits is not in the permitted values (0, 224, 256, 384, 512)")
    Sha2(e.expr, lit(numBits).expr)
  }

  /**
   * Calculates the cyclic redundancy check value  (CRC32) of a binary column and
   * returns the value as a bigint.
   *
   * @group misc_funcs
   * @since 1.5.0
   */
  def crc32(e: Column): Column = Crc32(e.expr)

  //////////////////////////////////////////////////////////////////////////////////////////////
  // String functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Concatenates input strings together into a single string.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def concat(exprs: Column*): Column = Concat(exprs.map(_.expr))

  /**
   * Concatenates input strings together into a single string, using the given separator.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def concat_ws(sep: String, exprs: Column*): Column = {
    ConcatWs(Literal.create(sep, StringType) +: exprs.map(_.expr))
  }

  /**
   * Computes the length of a given string / binary value.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def length(e: Column): Column = Length(e.expr)

  /**
   * Converts a string expression to lower case.
   *
   * @group string_funcs
   * @since 1.3.0
   */
  def lower(e: Column): Column = Lower(e.expr)

  /**
   * Converts a string expression to upper case.
   *
   * @group string_funcs
   * @since 1.3.0
   */
  def upper(e: Column): Column = Upper(e.expr)

  /**
   * Formats the number X to a format like '#,###,###.##', rounded to d decimal places,
   * and returns the result as a string.
   * If d is 0, the result has no decimal point or fractional part.
   * If d < 0, the result will be null.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def format_number(x: Column, d: Int): Column = FormatNumber(x.expr, lit(d).expr)

  /**
   * Computes the Levenshtein distance of the two given string columns.
   * @group string_funcs
   * @since 1.5.0
   */
  def levenshtein(l: Column, r: Column): Column = Levenshtein(l.expr, r.expr)

  /**
   * Computes the numeric value of the first character of the specified string column.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def ascii(e: Column): Column = Ascii(e.expr)

  /**
   * Trim the spaces from both ends for the specified string column.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def trim(e: Column): Column = StringTrim(e.expr)

  /**
   * Trim the spaces from left end for the specified string value.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def ltrim(e: Column): Column = StringTrimLeft(e.expr)

  /**
   * Trim the spaces from right end for the specified string value.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def rtrim(e: Column): Column = StringTrimRight(e.expr)

  /**
   * Formats the arguments in printf-style and returns the result as a string column.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  @scala.annotation.varargs
  def format_string(format: String, arguments: Column*): Column = {
    FormatString((lit(format) +: arguments).map(_.expr): _*)
  }

  /**
   * Locate the position of the first occurrence of substr column in the given string.
   * Returns null if either of the arguments are null.
   *
   * NOTE: The position is not zero based, but 1 based index, returns 0 if substr
   * could not be found in str.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def instr(str: Column, substring: String): Column = StringInstr(str.expr, lit(substring).expr)

  /**
   * Locate the position of the first occurrence of substr in a string column.
   *
   * NOTE: The position is not zero based, but 1 based index, returns 0 if substr
   * could not be found in str.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def locate(substr: String, str: Column): Column = {
    new StringLocate(lit(substr).expr, str.expr)
  }

  /**
   * Locate the position of the first occurrence of substr in a string column, after position pos.
   *
   * NOTE: The position is not zero based, but 1 based index. returns 0 if substr
   * could not be found in str.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def locate(substr: String, str: Column, pos: Int): Column = {
    StringLocate(lit(substr).expr, str.expr, lit(pos).expr)
  }


  /**
   * Extract a specific(idx) group identified by a java regex, from the specified string column.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def regexp_extract(e: Column, exp: String, groupIdx: Int): Column = {
    RegExpExtract(e.expr, lit(exp).expr, lit(groupIdx).expr)
  }

  /**
   * Replace all substrings of the specified string value that match regexp with rep.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def regexp_replace(e: Column, pattern: String, replacement: String): Column = {
    RegExpReplace(e.expr, lit(pattern).expr, lit(replacement).expr)
  }

  /**
   * Computes the BASE64 encoding of a binary column and returns it as a string column.
   * This is the reverse of unbase64.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def base64(e: Column): Column = Base64(e.expr)

  /**
   * Decodes a BASE64 encoded string column and returns it as a binary column.
   * This is the reverse of base64.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def unbase64(e: Column): Column = UnBase64(e.expr)

  /**
   * Left-padded with pad to a length of len.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def lpad(str: Column, len: Int, pad: String): Column = {
    StringLPad(str.expr, lit(len).expr, lit(pad).expr)
  }

  /**
   * Computes the first argument into a binary from a string using the provided character set
   * (one of 'US-ASCII', 'ISO-8859-1', 'UTF-8', 'UTF-16BE', 'UTF-16LE', 'UTF-16').
   * If either argument is null, the result will also be null.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def encode(value: Column, charset: String): Column = Encode(value.expr, lit(charset).expr)

  /**
   * Computes the first argument into a string from a binary using the provided character set
   * (one of 'US-ASCII', 'ISO-8859-1', 'UTF-8', 'UTF-16BE', 'UTF-16LE', 'UTF-16').
   * If either argument is null, the result will also be null.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def decode(value: Column, charset: String): Column = Decode(value.expr, lit(charset).expr)

  /**
   * Right-padded with pad to a length of len.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def rpad(str: Column, len: Int, pad: String): Column = {
    StringRPad(str.expr, lit(len).expr, lit(pad).expr)
  }

  /**
   * Repeats a string column n times, and returns it as a new string column.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def repeat(str: Column, n: Int): Column = {
    StringRepeat(str.expr, lit(n).expr)
  }

  /**
   * Splits str around pattern (pattern is a regular expression).
   * NOTE: pattern is a string represent the regular expression.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def split(str: Column, pattern: String): Column = {
    StringSplit(str.expr, lit(pattern).expr)
  }

  /**
   * Reversed the string for the specified value.
   *
   * @group string_funcs
   * @since 1.5.0
   */
  def reverse(str: Column): Column = {
    StringReverse(str.expr)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////
  // DateTime functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Returns the date that is numMonths after startDate.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def add_months(startDate: Column, numMonths: Int): Column =
    AddMonths(startDate.expr, Literal(numMonths))

  /**
   * Converts a date/timestamp/string to a value of string in the format specified by the date
   * format given by the second argument.
   *
   * A pattern could be for instance `dd.MM.yyyy` and could return a string like '18.03.1993'. All
   * pattern letters of [[java.text.SimpleDateFormat]] can be used.
   *
   * NOTE: Use when ever possible specialized functions like [[year]]. These benefit from a
   * specialized implementation.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def date_format(dateExpr: Column, format: String): Column =
    DateFormatClass(dateExpr.expr, Literal(format))

  /**
   * Converts a date/timestamp/string to a value of string in the format specified by the date
   * format given by the second argument.
   *
   * A pattern could be for instance `dd.MM.yyyy` and could return a string like '18.03.1993'. All
   * pattern letters of [[java.text.SimpleDateFormat]] can be used.
   *
   * NOTE: Use when ever possible specialized functions like [[year]]. These benefit from a
   * specialized implementation.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def date_format(dateColumnName: String, format: String): Column =
    date_format(Column(dateColumnName), format)

  /**
   * Returns the date that is `days` days after `start`
   * @group datetime_funcs
   * @since 1.5.0
   */
  def date_add(start: Column, days: Int): Column = DateAdd(start.expr, Literal(days))

  /**
   * Returns the date that is `days` days before `start`
   * @group datetime_funcs
   * @since 1.5.0
   */
  def date_sub(start: Column, days: Int): Column = DateSub(start.expr, Literal(days))

  /**
   * Extracts the year as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def year(e: Column): Column = Year(e.expr)

  /**
   * Extracts the year as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def year(columnName: String): Column = year(Column(columnName))

  /**
   * Extracts the quarter as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def quarter(e: Column): Column = Quarter(e.expr)

  /**
   * Extracts the quarter as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def quarter(columnName: String): Column = quarter(Column(columnName))

  /**
   * Extracts the month as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def month(e: Column): Column = Month(e.expr)

  /**
   * Extracts the month as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def month(columnName: String): Column = month(Column(columnName))

  /**
   * Extracts the day of the month as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def dayofmonth(e: Column): Column = DayOfMonth(e.expr)

  /**
   * Extracts the day of the month as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def dayofmonth(columnName: String): Column = dayofmonth(Column(columnName))

  /**
   * Extracts the day of the year as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def dayofyear(e: Column): Column = DayOfYear(e.expr)

  /**
   * Extracts the day of the year as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def dayofyear(columnName: String): Column = dayofyear(Column(columnName))

  /**
   * Extracts the hours as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def hour(e: Column): Column = Hour(e.expr)

  /**
   * Extracts the hours as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def hour(columnName: String): Column = hour(Column(columnName))

  /**
   * Given a date column, returns the last day of the month which the given date belongs to.
   * For example, input "2015-07-27" returns "2015-07-31" since July 31 is the last day of the
   * month in July 2015.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def last_day(e: Column): Column = LastDay(e.expr)

  /**
   * Extracts the minutes as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def minute(e: Column): Column = Minute(e.expr)

  /**
   * Extracts the minutes as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def minute(columnName: String): Column = minute(Column(columnName))

  /*
   * Returns number of months between dates `date1` and `date2`.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def months_between(date1: Column, date2: Column): Column = MonthsBetween(date1.expr, date2.expr)

  /**
   * Given a date column, returns the first date which is later than the value of the date column
   * that is on the specified day of the week.
   *
   * For example, `next_day('2015-07-27', "Sunday")` returns 2015-08-02 because that is the first
   * Sunday after 2015-07-27.
   *
   * Day of the week parameter is case insensitive, and accepts:
   * "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun".
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def next_day(date: Column, dayOfWeek: String): Column = NextDay(date.expr, lit(dayOfWeek).expr)

  /**
   * Extracts the seconds as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def second(e: Column): Column = Second(e.expr)

  /**
   * Extracts the seconds as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def second(columnName: String): Column = second(Column(columnName))

  /**
   * Extracts the week number as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def weekofyear(e: Column): Column = WeekOfYear(e.expr)

  /**
   * Extracts the week number as an integer from a given date/timestamp/string.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def weekofyear(columnName: String): Column = weekofyear(Column(columnName))

  /**
   * Converts the number of seconds from unix epoch (1970-01-01 00:00:00 UTC) to a string
   * representing the timestamp of that moment in the current system time zone in the given
   * format.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def from_unixtime(ut: Column): Column = FromUnixTime(ut.expr, Literal("yyyy-MM-dd HH:mm:ss"))

  /**
   * Converts the number of seconds from unix epoch (1970-01-01 00:00:00 UTC) to a string
   * representing the timestamp of that moment in the current system time zone in the given
   * format.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def from_unixtime(ut: Column, f: String): Column = FromUnixTime(ut.expr, Literal(f))

  /**
   * Gets current Unix timestamp in seconds.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def unix_timestamp(): Column = UnixTimestamp(CurrentTimestamp(), Literal("yyyy-MM-dd HH:mm:ss"))

  /**
   * Converts time string in format yyyy-MM-dd HH:mm:ss to Unix timestamp (in seconds),
   * using the default timezone and the default locale, return null if fail.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def unix_timestamp(s: Column): Column = UnixTimestamp(s.expr, Literal("yyyy-MM-dd HH:mm:ss"))

  /**
   * Convert time string with given pattern
   * (see [http://docs.oracle.com/javase/tutorial/i18n/format/simpleDateFormat.html])
   * to Unix time stamp (in seconds), return null if fail.
   * @group datetime_funcs
   * @since 1.5.0
   */
  def unix_timestamp(s: Column, p: String): Column = UnixTimestamp(s.expr, Literal(p))

  /*
   * Converts the column into DateType.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def to_date(e: Column): Column = ToDate(e.expr)

  /**
   * Returns date truncated to the unit specified by the format.
   *
   * @group datetime_funcs
   * @since 1.5.0
   */
  def trunc(date: Column, format: String): Column = TruncDate(date.expr, Literal(format))

  //////////////////////////////////////////////////////////////////////////////////////////////
  // Collection functions
  //////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Returns length of array or map
   * @group collection_funcs
   * @since 1.5.0
   */
  def size(columnName: String): Column = size(Column(columnName))

  /**
   * Returns length of array or map
   * @group collection_funcs
   * @since 1.5.0
   */
  def size(column: Column): Column = Size(column.expr)


  //////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////

  // scalastyle:off

  /* Use the following code to generate:
  (0 to 10).map { x =>
    val types = (1 to x).foldRight("RT")((i, s) => {s"A$i, $s"})
    val typeTags = (1 to x).map(i => s"A$i: TypeTag").foldLeft("RT: TypeTag")(_ + ", " + _)
    val inputTypes = (1 to x).foldRight("Nil")((i, s) => {s"ScalaReflection.schemaFor(typeTag[A$i]).dataType :: $s"})
    println(s"""
    /**
     * Defines a user-defined function of ${x} arguments as user-defined function (UDF).
     * The data types are automatically inferred based on the function's signature.
     *
     * @group udf_funcs
     * @since 1.3.0
     */
    def udf[$typeTags](f: Function$x[$types]): UserDefinedFunction = {
      val inputTypes = Try($inputTypes).getOrElse(Nil)
      UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
    }""")
  }

  (0 to 10).map { x =>
    val args = (1 to x).map(i => s"arg$i: Column").mkString(", ")
    val fTypes = Seq.fill(x + 1)("_").mkString(", ")
    val argsInUDF = (1 to x).map(i => s"arg$i.expr").mkString(", ")
    println(s"""
    /**
     * Call a Scala function of ${x} arguments as user-defined function (UDF). This requires
     * you to specify the return data type.
     *
     * @group udf_funcs
     * @since 1.3.0
     * @deprecated As of 1.5.0, since it's redundant with udf()
     */
    @deprecated("Use udf", "1.5.0")
    def callUDF(f: Function$x[$fTypes], returnType: DataType${if (args.length > 0) ", " + args else ""}): Column = {
      ScalaUDF(f, returnType, Seq($argsInUDF))
    }""")
  }
  }
  */
  /**
   * Defines a user-defined function of 0 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag](f: Function0[RT]): UserDefinedFunction = {
    val inputTypes = Try(Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 1 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag](f: Function1[A1, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 2 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag](f: Function2[A1, A2, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 3 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag](f: Function3[A1, A2, A3, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 4 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag](f: Function4[A1, A2, A3, A4, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 5 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag](f: Function5[A1, A2, A3, A4, A5, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 6 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag, A6: TypeTag](f: Function6[A1, A2, A3, A4, A5, A6, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: ScalaReflection.schemaFor(typeTag[A6]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 7 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag, A6: TypeTag, A7: TypeTag](f: Function7[A1, A2, A3, A4, A5, A6, A7, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: ScalaReflection.schemaFor(typeTag[A6]).dataType :: ScalaReflection.schemaFor(typeTag[A7]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 8 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag, A6: TypeTag, A7: TypeTag, A8: TypeTag](f: Function8[A1, A2, A3, A4, A5, A6, A7, A8, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: ScalaReflection.schemaFor(typeTag[A6]).dataType :: ScalaReflection.schemaFor(typeTag[A7]).dataType :: ScalaReflection.schemaFor(typeTag[A8]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 9 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag, A6: TypeTag, A7: TypeTag, A8: TypeTag, A9: TypeTag](f: Function9[A1, A2, A3, A4, A5, A6, A7, A8, A9, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: ScalaReflection.schemaFor(typeTag[A6]).dataType :: ScalaReflection.schemaFor(typeTag[A7]).dataType :: ScalaReflection.schemaFor(typeTag[A8]).dataType :: ScalaReflection.schemaFor(typeTag[A9]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  /**
   * Defines a user-defined function of 10 arguments as user-defined function (UDF).
   * The data types are automatically inferred based on the function's signature.
   *
   * @group udf_funcs
   * @since 1.3.0
   */
  def udf[RT: TypeTag, A1: TypeTag, A2: TypeTag, A3: TypeTag, A4: TypeTag, A5: TypeTag, A6: TypeTag, A7: TypeTag, A8: TypeTag, A9: TypeTag, A10: TypeTag](f: Function10[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, RT]): UserDefinedFunction = {
    val inputTypes = Try(ScalaReflection.schemaFor(typeTag[A1]).dataType :: ScalaReflection.schemaFor(typeTag[A2]).dataType :: ScalaReflection.schemaFor(typeTag[A3]).dataType :: ScalaReflection.schemaFor(typeTag[A4]).dataType :: ScalaReflection.schemaFor(typeTag[A5]).dataType :: ScalaReflection.schemaFor(typeTag[A6]).dataType :: ScalaReflection.schemaFor(typeTag[A7]).dataType :: ScalaReflection.schemaFor(typeTag[A8]).dataType :: ScalaReflection.schemaFor(typeTag[A9]).dataType :: ScalaReflection.schemaFor(typeTag[A10]).dataType :: Nil).getOrElse(Nil)
    UserDefinedFunction(f, ScalaReflection.schemaFor(typeTag[RT]).dataType, inputTypes)
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////

  /**
   * Call a Scala function of 0 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function0[_], returnType: DataType): Column = {
    ScalaUDF(f, returnType, Seq())
  }

  /**
   * Call a Scala function of 1 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function1[_, _], returnType: DataType, arg1: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr))
  }

  /**
   * Call a Scala function of 2 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function2[_, _, _], returnType: DataType, arg1: Column, arg2: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr))
  }

  /**
   * Call a Scala function of 3 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function3[_, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr))
  }

  /**
   * Call a Scala function of 4 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function4[_, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr))
  }

  /**
   * Call a Scala function of 5 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function5[_, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr))
  }

  /**
   * Call a Scala function of 6 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function6[_, _, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column, arg6: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr, arg6.expr))
  }

  /**
   * Call a Scala function of 7 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function7[_, _, _, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column, arg6: Column, arg7: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr, arg6.expr, arg7.expr))
  }

  /**
   * Call a Scala function of 8 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function8[_, _, _, _, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column, arg6: Column, arg7: Column, arg8: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr, arg6.expr, arg7.expr, arg8.expr))
  }

  /**
   * Call a Scala function of 9 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function9[_, _, _, _, _, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column, arg6: Column, arg7: Column, arg8: Column, arg9: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr, arg6.expr, arg7.expr, arg8.expr, arg9.expr))
  }

  /**
   * Call a Scala function of 10 arguments as user-defined function (UDF). This requires
   * you to specify the return data type.
   *
   * @group udf_funcs
   * @since 1.3.0
   * @deprecated As of 1.5.0, since it's redundant with udf()
   */
  @deprecated("Use udf", "1.5.0")
  def callUDF(f: Function10[_, _, _, _, _, _, _, _, _, _, _], returnType: DataType, arg1: Column, arg2: Column, arg3: Column, arg4: Column, arg5: Column, arg6: Column, arg7: Column, arg8: Column, arg9: Column, arg10: Column): Column = {
    ScalaUDF(f, returnType, Seq(arg1.expr, arg2.expr, arg3.expr, arg4.expr, arg5.expr, arg6.expr, arg7.expr, arg8.expr, arg9.expr, arg10.expr))
  }

  // scalastyle:on

  /**
   * Call an user-defined function.
   * Example:
   * {{{
   *  import org.apache.spark.sql._
   *
   *  val df = Seq(("id1", 1), ("id2", 4), ("id3", 5)).toDF("id", "value")
   *  val sqlContext = df.sqlContext
   *  sqlContext.udf.register("simpleUDF", (v: Int) => v * v)
   *  df.select($"id", callUDF("simpleUDF", $"value"))
   * }}}
   *
   * @group udf_funcs
   * @since 1.5.0
   */
  def callUDF(udfName: String, cols: Column*): Column = {
    UnresolvedFunction(udfName, cols.map(_.expr), isDistinct = false)
  }

  /**
   * Call an user-defined function.
   * Example:
   * {{{
   *  import org.apache.spark.sql._
   *
   *  val df = Seq(("id1", 1), ("id2", 4), ("id3", 5)).toDF("id", "value")
   *  val sqlContext = df.sqlContext
   *  sqlContext.udf.register("simpleUDF", (v: Int) => v * v)
   *  df.select($"id", callUdf("simpleUDF", $"value"))
   * }}}
   *
   * @group udf_funcs
   * @since 1.4.0
   * @deprecated As of 1.5.0, since it was not coherent to have two functions callUdf and callUDF
   */
  @deprecated("Use callUDF", "1.5.0")
  def callUdf(udfName: String, cols: Column*): Column = {
    // Note: we avoid using closures here because on file systems that are case-insensitive, the
    // compiled class file for the closure here will conflict with the one in callUDF (upper case).
    val exprs = new Array[Expression](cols.size)
    var i = 0
    while (i < cols.size) {
      exprs(i) = cols(i).expr
      i += 1
    }
    UnresolvedFunction(udfName, exprs, isDistinct = false)
  }
}
