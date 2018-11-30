package com.ibeifeng.senior.usertrack.spark.ad

import java.util.Properties

import com.ibeifeng.senior.usertrack.conf.ConfigurationManager
import com.ibeifeng.senior.usertrack.constant.Constants
import com.ibeifeng.senior.usertrack.spark.util.{SQLContextUtil, SparkConfUtil, SparkContextUtil}
import com.ibeifeng.senior.usertrack.util.DateUtils
import kafka.serializer.StringDecoder
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SaveMode
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka.KafkaUtils
import org.apache.spark.streaming.{Minutes, Seconds, StreamingContext}

import scala.reflect.ClassTag

/**
  * 广告流量实时统计分析：是一个SparakStreaming的程序，不存在taskID的参数
  * TODO: 大家自己把程序改成HA + Direct的模式，并测试运行
  * Created by ibf on 05/07.
  */
object AdClickRealTimeStateSparkOld {
  // kafka数据分隔符
  val delimeter = " "
  // 是否本地执行
  val isLocal = ConfigurationManager.getBoolean(Constants.SPARK_LOCAL)

  def main(args: Array[String]): Unit = {
    // 一、创建上下文
    // 1.1 设置相关变量
    val appName = Constants.SPARK_APP_NAME_AD
    // 1.2 创建上下文配置对象
    val conf = SparkConfUtil.generateSparkConf(appName, isLocal)
    conf.set("spark.streaming.blockInterval", "1s")
    // 1.3 获取上下文对象
    val sc = SparkContextUtil.getSparkContext(conf)
    // 1.4 创建Streaming上下文
    val ssc = new StreamingContext(sc, Seconds(30))
    // 1.5 给定checkpoint, 一般在hdfs上，课堂上给定本地文件系统
    val checkpointDir = s"/spark/170505/project/streaming/${System.currentTimeMillis()}"
    ssc.checkpoint(checkpointDir)

    // 二、读取Kafka数据形成DStream ==> 这里采用use Receiver的方式, 在实际工作中一般是HA + Direct的方式
    val kafkaParams = Map(
      "zookeeper.connect" -> ConfigurationManager.getProperty(Constants.KAFKA_ZOOKEEPER_URL),
      "group.id" -> appName,
      "zookeeper.connection.timeout.ms" -> "10000"
    )
    val topics: Map[String, Int] = ConfigurationManager
      .getProperty(Constants.KAFKA_AD_TOPICS)
      .split(",")
      .map(v => v.split(":"))
      .filter(_.length == 2)
      .map(a => (a(0), a(1).toInt))
      .toMap
    val dstream = KafkaUtils.createStream[String, String, StringDecoder, StringDecoder](
      ssc, kafkaParams, topics, StorageLevel.MEMORY_AND_DISK_SER_2
    ).map(_._2)

    // 三、数据处理
    // 3.1 数据格式转换
    val formatedDStream = this.formatAdRealTimeDStreamData(dstream)

    // 3.2 过滤黑名单数据
    val filteredDStream = this.filterByBlackList(formatedDStream)

    // 3.3 黑名单更新
    this.dynamicUpdateBlackList(filteredDStream)

    // 3.4 实时累加统计广告点击量
    /**
      * 实时统计每天、每个省份、每个城市的各个广告的点击量
      * 数据不涉及去重操作
      */
    val aggrDStream = this.calculateRealTimeState(filteredDStream)

    // 3.5 获取各个省份top5的广告点击
    /**
      * 最终需要的字段信息：省份、广告id、点击次数、时间
      */
    this.calculateProvinceTop5Ad(aggrDStream)

    // 3.6 获取最近一段时间的广告点击
    this.calculateAdClickCountByWindow(filteredDStream)

    // 四、启动Streaming程序
    ssc.start()
    ssc.awaitTermination()
  }

  /**
    * 实时统计最近10分钟的广告点击量
    * 使用window函数进行分析
    * -1. 窗口大小
    * window interval: 10 * 60 = 600s
    * -2. 滑动大小
    * slider interval: 1 * 60 = 60s
    * 需要字段信息：
    * 时间字符串(使用批次产生时间即可)、广告id、点击次数
    *
    * @param dstream
    */
  def calculateAdClickCountByWindow(dstream: DStream[AdClickRecord]): Unit = {
    // 1. 数据转换
    val mappedDStream = dstream.map(record => (record.adID, 1))
    // 2. 数据聚合: DStream[(广告id，点击次数)]
    val aggDStream: DStream[(Int, Int)] = mappedDStream.reduceByKeyAndWindow(
      (a: Int, b: Int) => a + b,
      (c: Int, d: Int) => c - d, // c: 上一个批次的执行结果， d：上一个批次和当前批次没有重叠部分RDD的执行结果； c - d表示：上一个批次和当前批次重叠部分的数据累加结果
      Minutes(10),
      Minutes(1)
    )
    // 3. 添加一个执行批次的字符串: DStream[(时间，广告id，点击次数)]
    val finalDStream: DStream[(String, Int, Int)] = aggDStream.transform((rdd, time) => {
      // 将time添加到rdd中即可
      val dateStr = DateUtils.parseLong2String(time.milliseconds, "yyyyMMddHHmmss")
      // rdd添加
      rdd.map {
        case (id, count) => (dateStr, id, count)
      }
    })
    // 4. 数据保存
    finalDStream.print(5)
  }

  /**
    * 获取各个省份top5的广告点击
    * 最终需要的字段信息：省份、广告id、点击次数、时间
    *
    * @param dStream
    */
  def calculateProvinceTop5Ad(dStream: DStream[((String, String, String, Int), Long)]): Unit = {
    // 1. 计算出点击量
    val dailyAndClickCountDStream = dStream
      .map {
        case ((date, province, _, adID), count) => ((date, province, adID), count)
      }
      .reduceByKey(_ + _)

    // 2. 获取每天、每个省份TOP5的广告点击信息 ==> 分组TopN的程序
    val top5ProvinceAdClickCountDStream = dailyAndClickCountDStream.transform(rdd => {
      rdd
        .map {
          case ((date, province, adID), count) => {
            ((date, province), (adID, count))
          }
        }
        .groupByKey()
        .flatMap {
          case ((date, province), iter) => {
            // 获取top5数据迭代器
            val top5Iter = iter.toList
              .sortBy(_._2)
              .takeRight(5)
            // 数据输出转换操作
            top5Iter.map(tuple => ((date, province, tuple._1), tuple._2))
          }
        }
    })

    // 3. 数据输出
    top5ProvinceAdClickCountDStream.print(5)
  }

  /**
    * 实时统计每天、每个省份、每个城市的各个广告的点击量
    * 数据不涉及去重操作
    *
    * @param dstream 过滤之后的数据
    * @return 返回结果类型是DStream[((日期，省份，城市，广告id), 被点击的次数)]
    */
  def calculateRealTimeState(dstream: DStream[AdClickRecord]): DStream[((String, String, String, Int), Long)] = {
    // 1. 将数据转换为key/value键值对
    val mappedDStream = dstream.map {
      case AdClickRecord(timestamp, province, city, _, adID) => {
        // 根据timestamp获取时间日期，格式为yyyyMMdd
        val date = DateUtils.parseLong2String(timestamp, DateUtils.DATEKEY_FORMAT)
        // 返回结果
        ((date, province, city, adID), 1)
      }
    }

    // 2. 累加统计结果数据
    val aggrDStream = mappedDStream
      .reduceByKey(_ + _)
      .updateStateByKey(
        (values: Seq[Int], state: Option[Long]) => {
          // 1. 获取当前key的传递的值
          val currentValue = values.sum

          // 2. 获取状态值
          val preValue = state.getOrElse(0L)

          // 3. 更新状态值并返回 ==> 如果这里的放回的是None的话，当前key的状态就不会被保存下去
          // TODO: 自己考虑一下昨天的数据(缓存这的状态值)，如果进行清空操作??? ===> 考虑currentValue为空以及比较当前的日期
          Some(currentValue + preValue)
        }
      )

    // 3. 数据存储
    // TODO: 自己存储到MySQL数据库中，表结构和当前的数据字段一致；插入方式为Insert Or Update
    aggrDStream.print(5)

    // 4. 处理后的DStream返回
    aggrDStream
  }

  /**
    * 根据保存在数据库中的黑名单进行数据过滤操作
    *
    * @param dstream
    * @return
    */
  def filterByBlackList(dstream: DStream[AdClickRecord]): DStream[AdClickRecord] = {
    dstream.transform(rdd => {
      // 一、读取数据库中黑名单用户
      val blackListRDD: RDD[(Int, Int)] = {
        val url = ConfigurationManager.getProperty(Constants.JDBC_URL)
        val table = "tb_black_users"
        val props = new Properties()
        props.put("user", ConfigurationManager.getProperty(Constants.JDBC_USER))
        props.put("password", ConfigurationManager.getProperty(Constants.JDBC_PASSWORD))
        val sc = rdd.sparkContext
        val sqlContext = SQLContextUtil.getInstance(sc, false)
        sqlContext
          .read
          .jdbc(url, table, props)
            .rdd.map(row => {
            val userID = row.getAs[Int]("user_id")
            val count = row.getAs[Int]("count")
            (userID, count)
          })
      }

      // 二、数据过滤
      rdd
        .map(record => (record.userID, record))
        .leftOuterJoin(blackListRDD)
        .filter(_._2._2.isEmpty) // 最终需要的是：过滤黑名单的数据，最终的结果在rdd里面不在blackListRDD中的数据
        .map(_._2._1)
    })
  }

  /**
    * 动态更新黑名单机制
    *
    * @param dstream
    */
  def dynamicUpdateBlackList(dstream: DStream[AdClickRecord]): Unit = {
    // 获取得到黑名单用户（过滤白名单的用户）
    val blackDStream = dstream
      .map(record => (record.userID, 1))
      .reduceByKeyAndWindow(
        (a: Int, b: Int) => a + b,
        Minutes(5), // 窗口大小，执行最近五分钟的数据
        Minutes(1) // 滑动大小，每隔一分钟执行一次
      )
      .filter(_._2 > 100)
      .transform(rdd => {
        // 1. 获取白名单用户
        // 从数据库获取白名单用户列表，这里采用模拟数据的方式
        val sc = rdd.sparkContext
        val whiteListRDD = sc.parallelize(0 until 1000)
        val broadcastOfWhiteList = sc.broadcast(whiteListRDD.collect())

        // 数据过滤
        // 方式一：基于广播变量的形式
        //        rdd.filter(tule => !broadcastOfWhiteList.value.contains(tule._1))
        // 方式二：通过left join进行数据过滤
        rdd
          .leftOuterJoin(whiteListRDD.map((_, 1)))
          .filter(_._2._2.isEmpty)
          .map(tuple => (tuple._1, tuple._2._1))
      })
    // 将数据进行保存操作
    /**
      * DStream数据保存要不转换为RDD保存，要不转换为DataFrame进行保存
      **/
    blackDStream.foreachRDD(rdd => {
      // TODO: 自己修改成为rdd的foreachpartition的方式进行数据输出操作
      // 将rdd转换为dataframe
      val sc = rdd.sparkContext
      val sqlContext = SQLContextUtil.getInstance(sc, false)
      import sqlContext.implicits._
      val df = rdd.toDF("user_id", "count")
      // dataframe保存
      val url = ConfigurationManager.getProperty(Constants.JDBC_URL)
      val table = "tb_black_users"
      val props = new Properties()
      props.put("user", ConfigurationManager.getProperty(Constants.JDBC_USER))
      props.put("password", ConfigurationManager.getProperty(Constants.JDBC_PASSWORD))
      df
        .write
        .mode(SaveMode.Append)
        .jdbc(url, table, props)
    })
  }

  /**
    * 将string转换为具体的数据类型
    *
    * @param dstream
    * @return
    */
  def formatAdRealTimeDStreamData(dstream: DStream[String]): DStream[AdClickRecord] = {
    /*dstream
      .map(line => {
        // a. 按照给定分隔符进行数据分割操作
        val arr = line.split(this.delimeter).map(_.trim).filter(_.nonEmpty)
        // b. 数据过滤操作
        if (arr.length == 5) {
          Some(
            AdClickRecord(
              arr(0).toLong,
              arr(1),
              arr(2),
              arr(3).toInt,
              arr(4).toInt
            )
          )
        } else {
          None
        }
      })
      .filter(_.isDefined)
      .map(_.get)*/

    dstream.flatMap(line => {
      // a. 按照给定分隔符进行数据分割操作
      val arr = line.split(this.delimeter).map(_.trim).filter(_.nonEmpty)
      // b. 数据过滤操作
      if (arr.length == 5) {
        Iterator.single(
          AdClickRecord(
            arr(0).toLong,
            arr(1),
            arr(2),
            arr(3).toInt,
            arr(4).toInt
          )
        ).toIterable
      } else {
        Iterable.empty[AdClickRecord]
      }
    })

    // TODO: 考虑spark程序开发优化操作，如果有连续性的多个map、filter、flatMap最好进行合并操作
  }
}

case class AdClickRecord(
                          timestamp: Long,
                          province: String,
                          city: String,
                          userID: Int,
                          adID: Int
                        )