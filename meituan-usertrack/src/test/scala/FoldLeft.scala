import java.util.Random

import scala.collection.mutable.ArrayBuffer

object FoldLeft {
  def main(args: Array[String]): Unit = {
    val random = new Random()
    val numbers = if (random.nextDouble() <= 0.05) {
      random.nextInt(20) + 1
    } else {
      1
    }
    println(numbers)
val msg = (0 until numbers).foldLeft(ArrayBuffer[String]())((buffer,i)=>{
  val str = Array("java","html","css","linux","hive","hadoop")
  val length = str.length
  buffer += str(random.nextInt(str.length))
  buffer
})
println(msg.toList)
  }

}
