package com.ibeifeng.senior.usertrack.spark.product



import java.util.Properties

import com.ibeifeng.senior.usertrack.conf.ConfigurationManager
import com.ibeifeng.senior.usertrack.constant.Constants
import com.ibeifeng.senior.usertrack.spark.util.{SparkConfUtil, SparkContextUtil}
import org.apache.spark.sql.hive.HiveContext

/**
  * Created by Administrator on 2018/1/5.
  */
object GroupBySort {
  def main(args: Array[String]): Unit = {
    val url = ConfigurationManager.getProperty(Constants.JDBC_URL)
    val user = ConfigurationManager.getProperty(Constants.JDBC_USER)
    val password = ConfigurationManager.getProperty(Constants.JDBC_PASSWORD)
    val table = "mydb.test"
    val prop:java.util.Properties = new Properties()
    prop.put("user",user)
    prop.put("password",password)
    val conf = SparkConfUtil.generateSparkConf(
      "GroupByAndSort",
      isLocal = true
    )
    val sc = SparkContextUtil.getSparkContext(conf)
    val sqLContext = new HiveContext(sc)
    sqLContext
      .read
      .jdbc(url,table,prop)
      .registerTempTable("test")

    sqLContext.sql("select * from test").show
    sqLContext.sql(
      """
        |select
        | id,time
        | from
        | (select id,time,
        | row_number() over(partition by id order by time desc) rank
        | from
        | test)t
        | where
        |  t.rank < 3
      """.stripMargin).show()
  }

}
