package com.ibeifeng.senior.usertrack.mock

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

import com.ibeifeng.senior.usertrack.conf.ConfigurationManager
import com.ibeifeng.senior.usertrack.constant.Constants
import kafka.producer.Producer
import kafka.producer.{KeyedMessage, ProducerConfig}

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/**
  * 模拟产生广告点击数据
  * Created by ibf on 03/16.
  */
object AdClickDataMock {
  // 分割字符串
  val delimeter = " "
  val topicName = "adLog"


  def main(args: Array[String]): Unit = {
    val running: AtomicBoolean = new AtomicBoolean(true)

    val brokerList = ConfigurationManager.getProperty(Constants.KAFKA_METADATA_BROKER_LIST)
    println(brokerList)
    val props = new Properties()
    props.put("metadata.broker.list", brokerList)
    props.put("serializer.class", "kafka.serializer.StringEncoder")

    val config: ProducerConfig = new ProducerConfig(props)
    val producer = new Producer[String, String](config)

    for (i <- 0 until 2) {
      new Thread(new Runnable {
        override def run(): Unit = {
          // 数据随机器
          val random = Random

          while (running.get()) {
            // 1. 随机数据
            val messages = generateMessage(random)
            var count = 0
            val threadName = Thread.currentThread().getName
            // 2. 发送
            for (message <- messages) {
              producer.send(message)
              count += 1
            }
            println(s"线程[${threadName}]总共生产了${count}条数据")
            // 3. 休息一下[50 250)
            Thread.sleep(random.nextInt(200) + 50)
          }
        }
      }).start()
    }

    // 运行2min后关闭
    Thread.sleep(3 * 60 * 60 * 1000)
    running.set(false)
    producer.close
  }

  /**
    * 产生一个随机的数据
    *
    * @return
    */
  def generateMessage(random: Random): List[KeyedMessage[String, String]] = {
    val key = random.nextInt(100).toString
    // 0-999
    val cityId = random.nextInt(1000)
    val province = s"province_${cityId % 100}"
    val city = s"city_${cityId}"
    val userId = random.nextInt(10000000)
    val adId = random.nextInt(10000)
    val str = s"${province}${delimeter}${city}${delimeter}${userId}${delimeter}${adId}"

    // 0.05的几率产生一次产生多条数据, num >= 1
    val numbers = if (random.nextDouble() <= 0.05) {
      random.nextInt(500) + 1
    } else {
      1
    }

    val msgs = (0 until numbers).foldLeft(ArrayBuffer[KeyedMessage[String, String]]())((buf, b) => {
      val timestamp = System.currentTimeMillis()
      val value = s"${timestamp}${delimeter}${str}"
      //topic， message key， partition key， message value
      val msg = KeyedMessage[String, String](topicName, key, key, value)

      buf += msg
      buf
    })

    //返回对象
    msgs.toList
  }
}
