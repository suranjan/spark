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

package org.apache.spark.ml.classification

import org.apache.spark.SparkException
import org.apache.spark.ml.{PredictorParams, PredictionModel, Predictor}
import org.apache.spark.ml.param.{ParamMap, ParamValidators, Param, DoubleParam}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.mllib.classification.{NaiveBayes => OldNaiveBayes}
import org.apache.spark.mllib.classification.{NaiveBayesModel => OldNaiveBayesModel}
import org.apache.spark.mllib.linalg._
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame

/**
 * Params for Naive Bayes Classifiers.
 */
private[ml] trait NaiveBayesParams extends PredictorParams {

  /**
   * The smoothing parameter.
   * (default = 1.0).
   * @group param
   */
  final val smoothing: DoubleParam = new DoubleParam(this, "smoothing", "The smoothing parameter.",
    ParamValidators.gtEq(0))

  /** @group getParam */
  final def getSmoothing: Double = $(smoothing)

  /**
   * The model type which is a string (case-sensitive).
   * Supported options: "multinomial" and "bernoulli".
   * (default = multinomial)
   * @group param
   */
  final val modelType: Param[String] = new Param[String](this, "modelType", "The model type " +
    "which is a string (case-sensitive). Supported options: multinomial (default) and bernoulli.",
    ParamValidators.inArray[String](OldNaiveBayes.supportedModelTypes.toArray))

  /** @group getParam */
  final def getModelType: String = $(modelType)
}

/**
 * Naive Bayes Classifiers.
 * It supports both Multinomial NB
 * ([[http://nlp.stanford.edu/IR-book/html/htmledition/naive-bayes-text-classification-1.html]])
 * which can handle finitely supported discrete data. For example, by converting documents into
 * TF-IDF vectors, it can be used for document classification. By making every vector a
 * binary (0/1) data, it can also be used as Bernoulli NB
 * ([[http://nlp.stanford.edu/IR-book/html/htmledition/the-bernoulli-model-1.html]]).
 * The input feature values must be nonnegative.
 */
class NaiveBayes(override val uid: String)
  extends Predictor[Vector, NaiveBayes, NaiveBayesModel]
  with NaiveBayesParams {

  def this() = this(Identifiable.randomUID("nb"))

  /**
   * Set the smoothing parameter.
   * Default is 1.0.
   * @group setParam
   */
  def setSmoothing(value: Double): this.type = set(smoothing, value)
  setDefault(smoothing -> 1.0)

  /**
   * Set the model type using a string (case-sensitive).
   * Supported options: "multinomial" and "bernoulli".
   * Default is "multinomial"
   */
  def setModelType(value: String): this.type = set(modelType, value)
  setDefault(modelType -> OldNaiveBayes.Multinomial)

  override protected def train(dataset: DataFrame): NaiveBayesModel = {
    val oldDataset: RDD[LabeledPoint] = extractLabeledPoints(dataset)
    val oldModel = OldNaiveBayes.train(oldDataset, $(smoothing), $(modelType))
    NaiveBayesModel.fromOld(oldModel, this)
  }

  override def copy(extra: ParamMap): NaiveBayes = defaultCopy(extra)
}

/**
 * Model produced by [[NaiveBayes]]
 */
class NaiveBayesModel private[ml] (
    override val uid: String,
    val pi: Vector,
    val theta: Matrix)
  extends PredictionModel[Vector, NaiveBayesModel] with NaiveBayesParams {

  import OldNaiveBayes.{Bernoulli, Multinomial}

  /**
   * Bernoulli scoring requires log(condprob) if 1, log(1-condprob) if 0.
   * This precomputes log(1.0 - exp(theta)) and its sum which are used for the linear algebra
   * application of this condition (in predict function).
   */
  private lazy val (thetaMinusNegTheta, negThetaSum) = $(modelType) match {
    case Multinomial => (None, None)
    case Bernoulli =>
      val negTheta = theta.map(value => math.log(1.0 - math.exp(value)))
      val ones = new DenseVector(Array.fill(theta.numCols){1.0})
      val thetaMinusNegTheta = theta.map { value =>
        value - math.log(1.0 - math.exp(value))
      }
      (Option(thetaMinusNegTheta), Option(negTheta.multiply(ones)))
    case _ =>
      // This should never happen.
      throw new UnknownError(s"Invalid modelType: ${$(modelType)}.")
  }

  override protected def predict(features: Vector): Double = {
    $(modelType) match {
      case Multinomial =>
        val prob = theta.multiply(features)
        BLAS.axpy(1.0, pi, prob)
        prob.argmax
      case Bernoulli =>
        features.foreachActive{ (index, value) =>
          if (value != 0.0 && value != 1.0) {
            throw new SparkException(
              s"Bernoulli naive Bayes requires 0 or 1 feature values but found $features")
          }
        }
        val prob = thetaMinusNegTheta.get.multiply(features)
        BLAS.axpy(1.0, pi, prob)
        BLAS.axpy(1.0, negThetaSum.get, prob)
        prob.argmax
      case _ =>
        // This should never happen.
        throw new UnknownError(s"Invalid modelType: ${$(modelType)}.")
    }
  }

  override def copy(extra: ParamMap): NaiveBayesModel = {
    copyValues(new NaiveBayesModel(uid, pi, theta).setParent(this.parent), extra)
  }

  override def toString: String = {
    s"NaiveBayesModel with ${pi.size} classes"
  }

}

private[ml] object NaiveBayesModel {

  /** Convert a model from the old API */
  def fromOld(
      oldModel: OldNaiveBayesModel,
      parent: NaiveBayes): NaiveBayesModel = {
    val uid = if (parent != null) parent.uid else Identifiable.randomUID("nb")
    val labels = Vectors.dense(oldModel.labels)
    val pi = Vectors.dense(oldModel.pi)
    val theta = new DenseMatrix(oldModel.labels.length, oldModel.theta(0).length,
      oldModel.theta.flatten, true)
    new NaiveBayesModel(uid, pi, theta)
  }
}
