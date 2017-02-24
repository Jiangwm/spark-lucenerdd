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
package org.zouzias.spark.lucenerdd.response

import com.twitter.algebird.TopKMonoid
import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.{OneToOneDependency, Partition, TaskContext}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SparkSession}
import org.apache.spark.sql.types.{DoubleType, StringType, StructField, StructType}
import org.apache.spark.storage.StorageLevel
import org.zouzias.spark.lucenerdd.models.SparkScoreDoc

/**
 * LuceneRDD response
 */
private[lucenerdd] class LuceneRDDResponse
  (protected val partitionsRDD: RDD[LuceneRDDResponsePartition],
   protected val ordering: Ordering[SparkScoreDoc])
  extends RDD[SparkScoreDoc](partitionsRDD.context,
    List(new OneToOneDependency(partitionsRDD))) {

  @DeveloperApi
  override def compute(split: Partition, context: TaskContext)
  : Iterator[SparkScoreDoc] = {
    firstParent[LuceneRDDResponsePartition].iterator(split, context).next().iterator()
  }

  override protected def getPartitions: Array[Partition] = partitionsRDD.partitions

  override protected def getPreferredLocations(s: Partition): Seq[String] =
    partitionsRDD.preferredLocations(s)

  override def cache(): this.type = {
    this.persist(StorageLevel.MEMORY_ONLY)
  }

  override def persist(newLevel: StorageLevel): this.type = {
    partitionsRDD.persist(newLevel)
    super.persist(newLevel)
    this
  }

  override def unpersist(blocking: Boolean = true): this.type = {
    partitionsRDD.unpersist(blocking)
    super.unpersist(blocking)
    this
  }

  /**
   * Use [[TopKMonoid]] to take
   * @param num
   * @return
   */
  override def take(num: Int): Array[SparkScoreDoc] = {
    val monoid = new TopKMonoid[SparkScoreDoc](num)(ordering)
    partitionsRDD.map(monoid.build(_))
      .reduce(monoid.plus).items.toArray
  }

  override def collect(): Array[SparkScoreDoc] = {
    val sz = partitionsRDD.map(_.size).sum().toInt
    val monoid = new TopKMonoid[SparkScoreDoc](sz)(ordering)
    partitionsRDD.map(monoid.build(_))
      .reduce(monoid.plus).items.toArray
  }

  def toDF()(implicit sqlContext: SQLContext): DataFrame = {
    val strfields = first().doc.getTextFields.toSeq
    val numFields = first().doc.getNumericFields.toSeq

    val schema = StructType(strfields.map(StructField(_, StringType, nullable = true))
      ++ numFields.map(StructField(_, DoubleType))) // FIXME: type should not be Double uniformly

    val rows = this.map { elem =>
      val strValues = Row.fromSeq(strfields.map(elem.doc.textField(_)))
      val numValues = Row.fromSeq(numFields.map(elem.doc.numericField(_)))

      Row.merge(strValues, numValues)
    }

    sqlContext.createDataFrame(rows, schema)
  }
}
