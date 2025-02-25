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

import scala.collection.Map

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions.codegen.{GeneratedExpressionCode, CodeGenContext}
import org.apache.spark.sql.types._

////////////////////////////////////////////////////////////////////////////////////////////////////
// This file defines all the expressions to extract values out of complex types.
// For example, getting a field out of an array, map, or struct.
////////////////////////////////////////////////////////////////////////////////////////////////////


object ExtractValue {
  /**
   * Returns the resolved `ExtractValue`. It will return one kind of concrete `ExtractValue`,
   * depend on the type of `child` and `extraction`.
   *
   *   `child`      |    `extraction`    |    concrete `ExtractValue`
   * ----------------------------------------------------------------
   *    Struct      |   Literal String   |        GetStructField
   * Array[Struct]  |   Literal String   |     GetArrayStructFields
   *    Array       |   Integral type    |         GetArrayItem
   *     Map        |      Any type      |         GetMapValue
   */
  def apply(
      child: Expression,
      extraction: Expression,
      resolver: Resolver): Expression = {

    (child.dataType, extraction) match {
      case (StructType(fields), NonNullLiteral(v, StringType)) =>
        val fieldName = v.toString
        val ordinal = findField(fields, fieldName, resolver)
        GetStructField(child, fields(ordinal).copy(name = fieldName), ordinal)

      case (ArrayType(StructType(fields), containsNull), NonNullLiteral(v, StringType)) =>
        val fieldName = v.toString
        val ordinal = findField(fields, fieldName, resolver)
        GetArrayStructFields(child, fields(ordinal).copy(name = fieldName),
          ordinal, fields.length, containsNull)

      case (_: ArrayType, _) if extraction.dataType.isInstanceOf[IntegralType] =>
        GetArrayItem(child, extraction)

      case (_: MapType, _) =>
        GetMapValue(child, extraction)

      case (otherType, _) =>
        val errorMsg = otherType match {
          case StructType(_) | ArrayType(StructType(_), _) =>
            s"Field name should be String Literal, but it's $extraction"
          case _: ArrayType =>
            s"Array index should be integral type, but it's ${extraction.dataType}"
          case other =>
            s"Can't extract value from $child"
        }
        throw new AnalysisException(errorMsg)
    }
  }

  /**
   * Find the ordinal of StructField, report error if no desired field or over one
   * desired fields are found.
   */
  private def findField(fields: Array[StructField], fieldName: String, resolver: Resolver): Int = {
    val checkField = (f: StructField) => resolver(f.name, fieldName)
    val ordinal = fields.indexWhere(checkField)
    if (ordinal == -1) {
      throw new AnalysisException(
        s"No such struct field $fieldName in ${fields.map(_.name).mkString(", ")}")
    } else if (fields.indexWhere(checkField, ordinal + 1) != -1) {
      throw new AnalysisException(
        s"Ambiguous reference to fields ${fields.filter(checkField).mkString(", ")}")
    } else {
      ordinal
    }
  }
}

/**
 * Returns the value of fields in the Struct `child`.
 *
 * No need to do type checking since it is handled by [[ExtractValue]].
 */
case class GetStructField(child: Expression, field: StructField, ordinal: Int)
  extends UnaryExpression {

  override def dataType: DataType = field.dataType
  override def nullable: Boolean = child.nullable || field.nullable
  override def toString: String = s"$child.${field.name}"

  protected override def nullSafeEval(input: Any): Any =
    input.asInstanceOf[InternalRow].get(ordinal, field.dataType)

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        if ($eval.isNullAt($ordinal)) {
          ${ev.isNull} = true;
        } else {
          ${ev.primitive} = ${ctx.getValue(eval, dataType, ordinal.toString)};
        }
      """
    })
  }
}

/**
 * Returns the array of value of fields in the Array of Struct `child`.
 *
 * No need to do type checking since it is handled by [[ExtractValue]].
 */
case class GetArrayStructFields(
    child: Expression,
    field: StructField,
    ordinal: Int,
    numFields: Int,
    containsNull: Boolean) extends UnaryExpression {

  override def dataType: DataType = ArrayType(field.dataType, containsNull)
  override def nullable: Boolean = child.nullable || containsNull || field.nullable
  override def toString: String = s"$child.${field.name}"

  protected override def nullSafeEval(input: Any): Any = {
    val array = input.asInstanceOf[ArrayData]
    val length = array.numElements()
    val result = new Array[Any](length)
    var i = 0
    while (i < length) {
      if (array.isNullAt(i)) {
        result(i) = null
      } else {
        val row = array.getStruct(i, numFields)
        if (row.isNullAt(ordinal)) {
          result(i) = null
        } else {
          result(i) = row.get(ordinal, field.dataType)
        }
      }
      i += 1
    }
    new GenericArrayData(result)
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    val arrayClass = classOf[GenericArrayData].getName
    nullSafeCodeGen(ctx, ev, eval => {
      s"""
        final int n = $eval.numElements();
        final Object[] values = new Object[n];
        for (int j = 0; j < n; j++) {
          if ($eval.isNullAt(j)) {
            values[j] = null;
          } else {
            final InternalRow row = $eval.getStruct(j, $numFields);
            if (row.isNullAt($ordinal)) {
              values[j] = null;
            } else {
              values[j] = ${ctx.getValue("row", field.dataType, ordinal.toString)};
            }
          }
        }
        ${ev.primitive} = new $arrayClass(values);
      """
    })
  }
}

/**
 * Returns the field at `ordinal` in the Array `child`.
 *
 * No need to do type checking since it is handled by [[ExtractValue]].
 */
case class GetArrayItem(child: Expression, ordinal: Expression) extends BinaryExpression {

  override def toString: String = s"$child[$ordinal]"

  override def left: Expression = child
  override def right: Expression = ordinal

  /** `Null` is returned for invalid ordinals. */
  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType.asInstanceOf[ArrayType].elementType

  protected override def nullSafeEval(value: Any, ordinal: Any): Any = {
    // TODO: consider using Array[_] for ArrayType child to avoid
    // boxing of primitives
    val baseValue = value.asInstanceOf[ArrayData]
    val index = ordinal.asInstanceOf[Number].intValue()
    if (index >= baseValue.numElements() || index < 0) {
      null
    } else {
      baseValue.get(index)
    }
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
      s"""
        final int index = (int) $eval2;
        if (index >= $eval1.numElements() || index < 0) {
          ${ev.isNull} = true;
        } else {
          ${ev.primitive} = ${ctx.getValue(eval1, dataType, "index")};
        }
      """
    })
  }
}

/**
 * Returns the value of key `key` in Map `child`.
 *
 * No need to do type checking since it is handled by [[ExtractValue]].
 */
case class GetMapValue(child: Expression, key: Expression) extends BinaryExpression {

  override def toString: String = s"$child[$key]"

  override def left: Expression = child
  override def right: Expression = key

  /** `Null` is returned for invalid ordinals. */
  override def nullable: Boolean = true

  override def dataType: DataType = child.dataType.asInstanceOf[MapType].valueType

  protected override def nullSafeEval(value: Any, ordinal: Any): Any = {
    val baseValue = value.asInstanceOf[Map[Any, _]]
    baseValue.get(ordinal).orNull
  }

  override def genCode(ctx: CodeGenContext, ev: GeneratedExpressionCode): String = {
    nullSafeCodeGen(ctx, ev, (eval1, eval2) => {
      s"""
        if ($eval1.contains($eval2)) {
          ${ev.primitive} = (${ctx.boxedType(dataType)})$eval1.apply($eval2);
        } else {
          ${ev.isNull} = true;
        }
      """
    })
  }
}
