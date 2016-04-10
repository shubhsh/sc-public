package relation
package compiler

import scala.collection.mutable.ArrayBuffer

import ch.epfl.data.sc.pardis
import pardis.optimization.RecursiveRuleBasedTransformer
import pardis.quasi.TypeParameters._
import pardis.types._
import PardisTypeImplicits._
import pardis.ir._

import relation.deep.RelationDSLOpsPackaged
import relation.shallow._  
import ArrayExtra.__for

class SchemaLowering(override val IR: RelationDSLOpsPackaged, val schemaAnalysis: SchemaAnalysis) extends RecursiveRuleBasedTransformer[RelationDSLOpsPackaged](IR) {
  
  implicit val ctx = IR // for quasiquotes
  
  import IR.Predef._
  import IR.{__new, field, __lambda}

  val symbolSchema = schemaAnalysis.symbolSchema

  private var recordsCount = 0

  def getClassTag = {
    recordsCount += 1
    StructTags.ClassTag(s"Record$recordsCount")
  }

  class Rec

  rewrite += symRule {
    case rel @ dsl"Relation.scan(${Constant(fileName)}, $schema1, ${Constant(delimiter)})" => 
      val schema = symbolSchema(rel)
      val scanner = dsl"new RelationScanner($fileName, ${delimiter.charAt(0)})"

      implicit val recTp: TypeRep[Rec] = new RecordType[Rec](getClassTag, None)
      def loadRecord: Rep[Rec] = __new[Rec](schema.columns.map(column => (column, false, dsl"$scanner.next_string()")): _*)

      dsl"""
        val arr = new Array[Rec](3)
        var i = 0
        while($scanner.hasNext) {
          val rec = $loadRecord
          arr(i) = rec
          i = i + 1
        }
        arr
      """.asInstanceOf[Rep[Any]]
  }

  rewrite += symRule {
    case rel @ dsl"(${ArrFromRelation(arr)}: Relation).project($schema1)" => 
      val schema = symbolSchema(rel)
      implicit val recTp: TypeRep[Rec] = new RecordType[Rec](getClassTag, None)
      def copyRecord(e: Rep[Any]): Rep[Rec] = __new[Rec](schema.columns.map(column => (column, false, field[String](e, column))): _*)
      val newArr = dsl"new Array[Rec]($arr.length)"
      dsl"""
        Range(0, $arr.length) foreach ${ __lambda[Int,Unit]((x: Rep[Int]) =>
          dsl"$newArr($x) = ${ copyRecord( dsl"$arr($x)" ) }"
        )}
      """
      newArr
  }

  rewrite += symRule {
    case rel @ dsl"(${rel1 @ ArrFromRelation(arr)}: Relation).select((x: Row) => x.getField($_, $name) == ($value: String))" => {
      implicit val recTp: TypeRep[Rec] = arr.tp.typeArguments(0).asInstanceOf[TypeRep[Rec]]
      dsl"""
        var size = 0
        for(j <- Range(0, $arr.length)) {
          val e = $arr(j)
          if(__struct_field[String](e, $name) == $value) {
            size = size + 1
          }
        }
        val arr = new Array[Rec](size)
        var i = 0
        for(j <- Range(0, arr.length)) {
          val e = $arr(j)
          if(__struct_field[String](e, $name) == $value) {
            arr(i) = $arr(j)
            i = i + 1
          }
        }
        arr
      """
      // dsl"println($value)"
    }
  }

  rewrite += symRule {
    case relr @ dsl"(${rel1 @ ArrFromRelation(arr1)}: Relation).join(${rel2 @ ArrFromRelation(arr2)}, $key1, $key2)" => {
      implicit val recTp: TypeRep[Rec] = new RecordType[Rec](getClassTag, None)
      val sch1 = symbolSchema(rel1)
      val sch2 = symbolSchema(rel2)
      val Constant(rightKey) = key2
      val sch1List = sch1.columns
      val sch2List = sch2.columns.filter(_ != rightKey)
      val newSchema = symbolSchema(relr)
      def joinRecords(e1: Rep[Any], e2: Rep[Any]): Rep[Rec] = {
        __new[Rec](sch1List.map(column => (column, false, field[String](e1, column))) ++ 
          sch2List.map(column => (column, false, field[String](e2, column))): _*)
      }
      def iterateOverTwoLists[T](f: (Rep[Any], Rep[Any]) => Rep[Unit]): Rep[Unit] = {
        dsl"""Range(0, $arr1.length) foreach ${
          __lambda[Int, Unit]({ (i: Rep[Int]) => 
          val e1 = dsl"$arr1($i)"
          dsl"""Range(0, $arr2.length) foreach ${
            __lambda[Int, Unit]({ (j: Rep[Int]) => 
              val e2 = dsl"$arr2($j)"
              f(e1, e2)
            })
          }"""
          })
        }"""
      }
      val size = newVar(dsl"0")
      iterateOverTwoLists((x, y) => 
        dsl"if(__struct_field[String]($x, $key1) == __struct_field[String]($y, $key2)) $size = $size + 1"
      )
      val arr = dsl"new Array[Rec]($size)"
      val index = newVar(dsl"0")
      iterateOverTwoLists((x, y) => 
        dsl"""
        if(__struct_field[String]($x, $key1) == __struct_field[String]($y, $key2)) {
          $arr($index) = ${joinRecords(x, y)}
          $index = $index + 1
        }"""
      )
      arr
    }
  }

  rewrite += symRule {
    case dsl"(${ArrFromRelation(arr)}: Relation).print" => {
      implicit val recTp: TypeRep[Rec] = arr.tp.typeArguments(0).asInstanceOf[TypeRep[Rec]]
      dsl"""
        for(j <- Range(0, $arr.length)) {
          val e = $arr(j)
          println(e)
        }
      """
      // dsl"println($value)"
    }
  }

  object ArrFromRelation {
    def unapply(x: Rep[Relation]): Option[Rep[Array[Rec]]] = x match {
      case dsl"$ls: Relation" =>
        Some(apply[Any](ls).asInstanceOf[Rep[Array[Rec]]])
      case _ => None
    }
  }

}
