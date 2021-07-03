package example

object Main extends App {
  println(Lists.sum(List(1, 3, 2)))
  println(Lists.sum(List(1)))
  println(Lists.sum(List.empty))

  println(Lists.max(List(1, 2, 3)))
  println(Lists.max(List(1)))
  try {
    println(Lists.max(List.empty))
  } catch {
    case _: NoSuchElementException => println("No such element exception")
    case e: Exception => throw e
  }
}
