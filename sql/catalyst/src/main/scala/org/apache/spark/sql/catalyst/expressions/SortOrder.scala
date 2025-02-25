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

package org.apache.spark.sql.catalyst.expressions

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.unsafe.sort.PrefixComparators.DoublePrefixComparator

abstract sealed class SortDirection
case object Ascending extends SortDirection
case object Descending extends SortDirection

/**
 * An expression that can be used to sort a tuple.  This class extends expression primarily so that
 * transformations over expression will descend into its child.
 */
case class SortOrder(child: Expression, direction: SortDirection)
  extends UnaryExpression with Unevaluable {

  /** Sort order is not foldable because we don't have an eval for it. */
  override def foldable: Boolean = false

  override def dataType: DataType = child.dataType
  override def nullable: Boolean = child.nullable

  override def toString: String = s"$child ${if (direction == Ascending) "ASC" else "DESC"}"

  def isAscending: Boolean = direction == Ascending
}

/**
 * An expression to generate a 64-bit long prefix used in sorting.
 */
case class SortPrefix(child: SortOrder) extends UnaryExpression {

  override def eval(input: InternalRow): Any = throw new UnsupportedOperationException

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val childCode = child.child.gen(ctx)
    val input = childCode.primitive
    val DoublePrefixCmp = classOf[DoublePrefixComparator].getName

    val (nullValue: Long, prefixCode: String) = child.child.dataType match {
      case BooleanType =>
        (Long.MinValue, s"$input ? 1L : 0L")
      case _: IntegralType =>
        (Long.MinValue, s"(long) $input")
      case FloatType | DoubleType =>
        (DoublePrefixComparator.computePrefix(Double.NegativeInfinity),
          s"$DoublePrefixCmp.computePrefix((double)$input)")
      case StringType => (0L, s"$input.getPrefix()")
      case _ => (0L, "0L")
    }

    childCode.code +
    s"""
      |long ${ev.primitive} = ${nullValue}L;
      |boolean ${ev.isNull} = false;
      |if (!${childCode.isNull}) {
      |  ${ev.primitive} = $prefixCode;
      |}
    """.stripMargin
  }

  override def dataType: DataType = LongType
}
