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

package org.apache.spark.mllib.clustering

import java.util.Random

import breeze.linalg.{DenseMatrix => BDM, DenseVector => BDV, normalize, sum}
import breeze.numerics.{abs, exp}
import breeze.stats.distributions.{Gamma, RandBasis}

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.graphx._
import org.apache.spark.graphx.impl.GraphImpl
import org.apache.spark.mllib.impl.PeriodicGraphCheckpointer
import org.apache.spark.mllib.linalg.{DenseVector, Matrices, SparseVector, Vector, Vectors}
import org.apache.spark.rdd.RDD

/**
 * :: DeveloperApi ::
 *
 * An LDAOptimizer specifies which optimization/learning/inference algorithm to use, and it can
 * hold optimizer-specific parameters for users to set.
 */
@DeveloperApi
sealed trait LDAOptimizer {

  /*
    DEVELOPERS NOTE:

    An LDAOptimizer contains an algorithm for LDA and performs the actual computation, which
    stores internal data structure (Graph or Matrix) and other parameters for the algorithm.
    The interface is isolated to improve the extensibility of LDA.
   */

  /**
   * Initializer for the optimizer. LDA passes the common parameters to the optimizer and
   * the internal structure can be initialized properly.
   */
  private[clustering] def initialize(docs: RDD[(Long, Vector)], lda: LDA): LDAOptimizer

  private[clustering] def next(): LDAOptimizer

  private[clustering] def getLDAModel(iterationTimes: Array[Double]): LDAModel
}

/**
 * :: DeveloperApi ::
 *
 * Optimizer for EM algorithm which stores data + parameter graph, plus algorithm parameters.
 *
 * Currently, the underlying implementation uses Expectation-Maximization (EM), implemented
 * according to the Asuncion et al. (2009) paper referenced below.
 *
 * References:
 *  - Original LDA paper (journal version):
 *    Blei, Ng, and Jordan.  "Latent Dirichlet Allocation."  JMLR, 2003.
 *     - This class implements their "smoothed" LDA model.
 *  - Paper which clearly explains several algorithms, including EM:
 *    Asuncion, Welling, Smyth, and Teh.
 *    "On Smoothing and Inference for Topic Models."  UAI, 2009.
 *
 */
@DeveloperApi
final class EMLDAOptimizer extends LDAOptimizer {

  import LDA._

  /**
   * The following fields will only be initialized through the initialize() method
   */
  private[clustering] var graph: Graph[TopicCounts, TokenCount] = null
  private[clustering] var k: Int = 0
  private[clustering] var vocabSize: Int = 0
  private[clustering] var docConcentration: Double = 0
  private[clustering] var topicConcentration: Double = 0
  private[clustering] var checkpointInterval: Int = 10
  private var graphCheckpointer: PeriodicGraphCheckpointer[TopicCounts, TokenCount] = null

  /**
   * Compute bipartite term/doc graph.
   */
  override private[clustering] def initialize(docs: RDD[(Long, Vector)], lda: LDA): LDAOptimizer = {
    val docConcentration = lda.getDocConcentration(0)
    require({
      lda.getDocConcentration.toArray.forall(_ == docConcentration)
    }, "EMLDAOptimizer currently only supports symmetric document-topic priors")

    val topicConcentration = lda.getTopicConcentration
    val k = lda.getK

    // Note: The restriction > 1.0 may be relaxed in the future (allowing sparse solutions),
    // but values in (0,1) are not yet supported.
    require(docConcentration > 1.0 || docConcentration == -1.0, s"LDA docConcentration must be" +
      s" > 1.0 (or -1 for auto) for EM Optimizer, but was set to $docConcentration")
    require(topicConcentration > 1.0 || topicConcentration == -1.0, s"LDA topicConcentration " +
      s"must be > 1.0 (or -1 for auto) for EM Optimizer, but was set to $topicConcentration")

    this.docConcentration = if (docConcentration == -1) (50.0 / k) + 1.0 else docConcentration
    this.topicConcentration = if (topicConcentration == -1) 1.1 else topicConcentration
    val randomSeed = lda.getSeed

    // For each document, create an edge (Document -> Term) for each unique term in the document.
    val edges: RDD[Edge[TokenCount]] = docs.flatMap { case (docID: Long, termCounts: Vector) =>
      // Add edges for terms with non-zero counts.
      termCounts.toBreeze.activeIterator.filter(_._2 != 0.0).map { case (term, cnt) =>
        Edge(docID, term2index(term), cnt)
      }
    }

    // Create vertices.
    // Initially, we use random soft assignments of tokens to topics (random gamma).
    val docTermVertices: RDD[(VertexId, TopicCounts)] = {
      val verticesTMP: RDD[(VertexId, TopicCounts)] =
        edges.mapPartitionsWithIndex { case (partIndex, partEdges) =>
          val random = new Random(partIndex + randomSeed)
          partEdges.flatMap { edge =>
            val gamma = normalize(BDV.fill[Double](k)(random.nextDouble()), 1.0)
            val sum = gamma * edge.attr
            Seq((edge.srcId, sum), (edge.dstId, sum))
          }
        }
      verticesTMP.reduceByKey(_ + _)
    }

    // Partition such that edges are grouped by document
    this.graph = Graph(docTermVertices, edges).partitionBy(PartitionStrategy.EdgePartition1D)
    this.k = k
    this.vocabSize = docs.take(1).head._2.size
    this.checkpointInterval = lda.getCheckpointInterval
    this.graphCheckpointer = new PeriodicGraphCheckpointer[TopicCounts, TokenCount](
      checkpointInterval, graph.vertices.sparkContext)
    this.graphCheckpointer.update(this.graph)
    this.globalTopicTotals = computeGlobalTopicTotals()
    this
  }

  override private[clustering] def next(): EMLDAOptimizer = {
    require(graph != null, "graph is null, EMLDAOptimizer not initialized.")

    val eta = topicConcentration
    val W = vocabSize
    val alpha = docConcentration

    val N_k = globalTopicTotals
    val sendMsg: EdgeContext[TopicCounts, TokenCount, (Boolean, TopicCounts)] => Unit =
      (edgeContext) => {
        // Compute N_{wj} gamma_{wjk}
        val N_wj = edgeContext.attr
        // E-STEP: Compute gamma_{wjk} (smoothed topic distributions), scaled by token count
        // N_{wj}.
        val scaledTopicDistribution: TopicCounts =
          computePTopic(edgeContext.srcAttr, edgeContext.dstAttr, N_k, W, eta, alpha) *= N_wj
        edgeContext.sendToDst((false, scaledTopicDistribution))
        edgeContext.sendToSrc((false, scaledTopicDistribution))
      }
    // This is a hack to detect whether we could modify the values in-place.
    // TODO: Add zero/seqOp/combOp option to aggregateMessages. (SPARK-5438)
    val mergeMsg: ((Boolean, TopicCounts), (Boolean, TopicCounts)) => (Boolean, TopicCounts) =
      (m0, m1) => {
        val sum =
          if (m0._1) {
            m0._2 += m1._2
          } else if (m1._1) {
            m1._2 += m0._2
          } else {
            m0._2 + m1._2
          }
        (true, sum)
      }
    // M-STEP: Aggregation computes new N_{kj}, N_{wk} counts.
    val docTopicDistributions: VertexRDD[TopicCounts] =
      graph.aggregateMessages[(Boolean, TopicCounts)](sendMsg, mergeMsg)
        .mapValues(_._2)
    // Update the vertex descriptors with the new counts.
    val newGraph = GraphImpl.fromExistingRDDs(docTopicDistributions, graph.edges)
    graph = newGraph
    graphCheckpointer.update(newGraph)
    globalTopicTotals = computeGlobalTopicTotals()
    this
  }

  /**
   * Aggregate distributions over topics from all term vertices.
   *
   * Note: This executes an action on the graph RDDs.
   */
  private[clustering] var globalTopicTotals: TopicCounts = null

  private def computeGlobalTopicTotals(): TopicCounts = {
    val numTopics = k
    graph.vertices.filter(isTermVertex).values.fold(BDV.zeros[Double](numTopics))(_ += _)
  }

  override private[clustering] def getLDAModel(iterationTimes: Array[Double]): LDAModel = {
    require(graph != null, "graph is null, EMLDAOptimizer not initialized.")
    this.graphCheckpointer.deleteAllCheckpoints()
    // This assumes gammaShape = 100 in OnlineLDAOptimizer to ensure equivalence in LDAModel.toLocal
    // conversion
    new DistributedLDAModel(this.graph, this.globalTopicTotals, this.k, this.vocabSize,
      Vectors.dense(Array.fill(this.k)(this.docConcentration)), this.topicConcentration,
      100, iterationTimes)
  }
}


/**
 * :: DeveloperApi ::
 *
 * An online optimizer for LDA. The Optimizer implements the Online variational Bayes LDA
 * algorithm, which processes a subset of the corpus on each iteration, and updates the term-topic
 * distribution adaptively.
 *
 * Original Online LDA paper:
 *   Hoffman, Blei and Bach, "Online Learning for Latent Dirichlet Allocation." NIPS, 2010.
 */
@DeveloperApi
final class OnlineLDAOptimizer extends LDAOptimizer {

  // LDA common parameters
  private var k: Int = 0
  private var corpusSize: Long = 0
  private var vocabSize: Int = 0

  /** alias for docConcentration */
  private var alpha: Vector = Vectors.dense(0)

  /** (private[clustering] for debugging)  Get docConcentration */
  private[clustering] def getAlpha: Vector = alpha

  /** alias for topicConcentration */
  private var eta: Double = 0

  /** (private[clustering] for debugging)  Get topicConcentration */
  private[clustering] def getEta: Double = eta

  private var randomGenerator: java.util.Random = null

  // Online LDA specific parameters
  // Learning rate is: (tau0 + t)^{-kappa}
  private var tau0: Double = 1024
  private var kappa: Double = 0.51
  private var miniBatchFraction: Double = 0.05

  // internal data structure
  private var docs: RDD[(Long, Vector)] = null

  /** Dirichlet parameter for the posterior over topics */
  private var lambda: BDM[Double] = null

  /** (private[clustering] for debugging) Get parameter for topics */
  private[clustering] def getLambda: BDM[Double] = lambda

  /** Current iteration (count of invocations of [[next()]]) */
  private var iteration: Int = 0
  private var gammaShape: Double = 100

  /**
   * A (positive) learning parameter that downweights early iterations. Larger values make early
   * iterations count less.
   */
  def getTau0: Double = this.tau0

  /**
   * A (positive) learning parameter that downweights early iterations. Larger values make early
   * iterations count less.
   * Default: 1024, following the original Online LDA paper.
   */
  def setTau0(tau0: Double): this.type = {
    require(tau0 > 0, s"LDA tau0 must be positive, but was set to $tau0")
    this.tau0 = tau0
    this
  }

  /**
   * Learning rate: exponential decay rate
   */
  def getKappa: Double = this.kappa

  /**
   * Learning rate: exponential decay rate---should be between
   * (0.5, 1.0] to guarantee asymptotic convergence.
   * Default: 0.51, based on the original Online LDA paper.
   */
  def setKappa(kappa: Double): this.type = {
    require(kappa >= 0, s"Online LDA kappa must be nonnegative, but was set to $kappa")
    this.kappa = kappa
    this
  }

  /**
   * Mini-batch fraction, which sets the fraction of document sampled and used in each iteration
   */
  def getMiniBatchFraction: Double = this.miniBatchFraction

  /**
   * Mini-batch fraction in (0, 1], which sets the fraction of document sampled and used in
   * each iteration.
   *
   * Note that this should be adjusted in synch with [[LDA.setMaxIterations()]]
   * so the entire corpus is used.  Specifically, set both so that
   * maxIterations * miniBatchFraction >= 1.
   *
   * Default: 0.05, i.e., 5% of total documents.
   */
  def setMiniBatchFraction(miniBatchFraction: Double): this.type = {
    require(miniBatchFraction > 0.0 && miniBatchFraction <= 1.0,
      s"Online LDA miniBatchFraction must be in range (0,1], but was set to $miniBatchFraction")
    this.miniBatchFraction = miniBatchFraction
    this
  }

  /**
   * (private[clustering])
   * Set the Dirichlet parameter for the posterior over topics.
   * This is only used for testing now. In the future, it can help support training stop/resume.
   */
  private[clustering] def setLambda(lambda: BDM[Double]): this.type = {
    this.lambda = lambda
    this
  }

  /**
   * (private[clustering])
   * Used for random initialization of the variational parameters.
   * Larger value produces values closer to 1.0.
   * This is only used for testing currently.
   */
  private[clustering] def setGammaShape(shape: Double): this.type = {
    this.gammaShape = shape
    this
  }

  override private[clustering] def initialize(
      docs: RDD[(Long, Vector)],
      lda: LDA): OnlineLDAOptimizer = {
    this.k = lda.getK
    this.corpusSize = docs.count()
    this.vocabSize = docs.first()._2.size
    this.alpha = if (lda.getDocConcentration.size == 1) {
      if (lda.getDocConcentration(0) == -1) Vectors.dense(Array.fill(k)(1.0 / k))
      else {
        require(lda.getDocConcentration(0) >= 0, s"all entries in alpha must be >=0, got: $alpha")
        Vectors.dense(Array.fill(k)(lda.getDocConcentration(0)))
      }
    } else {
      require(lda.getDocConcentration.size == k, s"alpha must have length k, got: $alpha")
      lda.getDocConcentration.foreachActive { case (_, x) =>
        require(x >= 0, s"all entries in alpha must be >= 0, got: $alpha")
      }
      lda.getDocConcentration
    }
    this.eta = if (lda.getTopicConcentration == -1) 1.0 / k else lda.getTopicConcentration
    this.randomGenerator = new Random(lda.getSeed)

    this.docs = docs

    // Initialize the variational distribution q(beta|lambda)
    this.lambda = getGammaMatrix(k, vocabSize)
    this.iteration = 0
    this
  }

  override private[clustering] def next(): OnlineLDAOptimizer = {
    val batch = docs.sample(withReplacement = true, miniBatchFraction, randomGenerator.nextLong())
    if (batch.isEmpty()) return this
    submitMiniBatch(batch)
  }

  /**
   * Submit a subset (like 1%, decide by the miniBatchFraction) of the corpus to the Online LDA
   * model, and it will update the topic distribution adaptively for the terms appearing in the
   * subset.
   */
  private[clustering] def submitMiniBatch(batch: RDD[(Long, Vector)]): OnlineLDAOptimizer = {
    iteration += 1
    val k = this.k
    val vocabSize = this.vocabSize
    val expElogbeta = exp(LDAUtils.dirichletExpectation(lambda)).t
    val alpha = this.alpha.toBreeze
    val gammaShape = this.gammaShape

    val stats: RDD[(BDM[Double], List[BDV[Double]])] = batch.mapPartitions { docs =>
      val nonEmptyDocs = docs.filter(_._2.numNonzeros > 0)

      val stat = BDM.zeros[Double](k, vocabSize)
      var gammaPart = List[BDV[Double]]()
      nonEmptyDocs.zipWithIndex.foreach { case ((_, termCounts: Vector), idx: Int) =>
        val ids: List[Int] = termCounts match {
          case v: DenseVector => (0 until v.size).toList
          case v: SparseVector => v.indices.toList
        }
        val (gammad, sstats) = OnlineLDAOptimizer.variationalTopicInference(
          termCounts, expElogbeta, alpha, gammaShape, k)
        stat(::, ids) := stat(::, ids).toDenseMatrix + sstats
        gammaPart = gammad :: gammaPart
      }
      Iterator((stat, gammaPart))
    }
    val statsSum: BDM[Double] = stats.map(_._1).reduce(_ += _)
    val gammat: BDM[Double] = breeze.linalg.DenseMatrix.vertcat(
      stats.map(_._2).reduce(_ ++ _).map(_.toDenseMatrix): _*)
    val batchResult = statsSum :* expElogbeta.t

    // Note that this is an optimization to avoid batch.count
    updateLambda(batchResult, (miniBatchFraction * corpusSize).ceil.toInt)
    this
  }

  /**
   * Update lambda based on the batch submitted. batchSize can be different for each iteration.
   */
  private def updateLambda(stat: BDM[Double], batchSize: Int): Unit = {
    // weight of the mini-batch.
    val weight = rho()

    // Update lambda based on documents.
    lambda := (1 - weight) * lambda +
      weight * (stat * (corpusSize.toDouble / batchSize.toDouble) + eta)
  }

  /** Calculates learning rate rho, which decays as a function of [[iteration]] */
  private def rho(): Double = {
    math.pow(getTau0 + this.iteration, -getKappa)
  }

  /**
   * Get a random matrix to initialize lambda
   */
  private def getGammaMatrix(row: Int, col: Int): BDM[Double] = {
    val randBasis = new RandBasis(new org.apache.commons.math3.random.MersenneTwister(
      randomGenerator.nextLong()))
    val gammaRandomGenerator = new Gamma(gammaShape, 1.0 / gammaShape)(randBasis)
    val temp = gammaRandomGenerator.sample(row * col).toArray
    new BDM[Double](col, row, temp).t
  }

  override private[clustering] def getLDAModel(iterationTimes: Array[Double]): LDAModel = {
    new LocalLDAModel(Matrices.fromBreeze(lambda).transpose, alpha, eta, gammaShape)
  }

}

/**
 * Serializable companion object containing helper methods and shared code for
 * [[OnlineLDAOptimizer]] and [[LocalLDAModel]].
 */
private[clustering] object OnlineLDAOptimizer {
  /**
   * Uses variational inference to infer the topic distribution `gammad` given the term counts
   * for a document. `termCounts` must contain at least one non-zero entry, otherwise Breeze will
   * throw a BLAS error.
   *
   * An optimization (Lee, Seung: Algorithms for non-negative matrix factorization, NIPS 2001)
   * avoids explicit computation of variational parameter `phi`.
   * @see [[http://citeseerx.ist.psu.edu/viewdoc/summary?doi=10.1.1.31.7566]]
   */
  private[clustering] def variationalTopicInference(
      termCounts: Vector,
      expElogbeta: BDM[Double],
      alpha: breeze.linalg.Vector[Double],
      gammaShape: Double,
      k: Int): (BDV[Double], BDM[Double]) = {
    val (ids: List[Int], cts: Array[Double]) = termCounts match {
      case v: DenseVector => ((0 until v.size).toList, v.values)
      case v: SparseVector => (v.indices.toList, v.values)
    }
    // Initialize the variational distribution q(theta|gamma) for the mini-batch
    val gammad: BDV[Double] =
      new Gamma(gammaShape, 1.0 / gammaShape).samplesVector(k)                   // K
    val expElogthetad: BDV[Double] = exp(LDAUtils.dirichletExpectation(gammad))  // K
    val expElogbetad = expElogbeta(ids, ::).toDenseMatrix                        // ids * K

    val phinorm: BDV[Double] = expElogbetad * expElogthetad :+ 1e-100            // ids
    var meanchange = 1D
    val ctsVector = new BDV[Double](cts)                                         // ids

    // Iterate between gamma and phi until convergence
    while (meanchange > 1e-3) {
      val lastgamma = gammad.copy
      //        K                  K * ids               ids
      gammad := (expElogthetad :* (expElogbetad.t * (ctsVector :/ phinorm))) :+ alpha
      expElogthetad := exp(LDAUtils.dirichletExpectation(gammad))
      phinorm := expElogbetad * expElogthetad :+ 1e-100
      meanchange = sum(abs(gammad - lastgamma)) / k
    }

    val sstatsd = expElogthetad.asDenseMatrix.t * (ctsVector :/ phinorm).asDenseMatrix
    (gammad, sstatsd)
  }
}
