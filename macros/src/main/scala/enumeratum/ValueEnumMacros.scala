package enumeratum

import enumeratum.ContextUtils.Context

import scala.reflect.ClassTag

object ValueEnumMacros {

  /**
   * Finds ValueEntryType-typed objects in scope that have literal value:Int implementations
   *
   * Note, requires the ValueEntryType to have a 'value' member that has a literal value
   */
  def findIntValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, ContextUtils.CTInt, Int](c)(identity)
  }

  /**
   * Finds ValueEntryType-typed objects in scope that have literal value:Long implementations
   *
   * Note, requires the ValueEntryType to have a 'value' member that has a literal value
   */
  def findLongValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, ContextUtils.CTLong, Long](c)(identity)
  }

  /**
   * Finds ValueEntryType-typed objects in scope that have literal value:Short implementations
   *
   * Note
   *
   *  - requires the ValueEntryType to have a 'value' member that has a literal value
   *  - the Short value should be a literal Int (do no need to cast .toShort).
   */
  def findShortValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, ContextUtils.CTInt, Short](c)(_.toShort) // do a transform because there is no such thing as Short literals
  }

  /**
    * Finds ValueEntryType-typed objects in scope that have literal value:Short implementations
    *
    * Note
    *
    *  - requires the ValueEntryType to have a 'value' member that has a literal value
    *  - the Short value should be a literal Int (do no need to cast .toShort).
    */
  def findByteValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, ContextUtils.CTByte, Byte](c)(_.toByte) // do a transform because there is no such thing as Short literals
  }

  /**
    * Finds ValueEntryType-typed objects in scope that have literal value:Short implementations
    *
    * Note
    *
    *  - requires the ValueEntryType to have a 'value' member that has a literal value
    *  - the Short value should be a literal Int (do no need to cast .toShort).
    */
  def findBooleanValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, ContextUtils.CTBoolean, Boolean](c)(identity)
  }

  /**
   * Finds ValueEntryType-typed objects in scope that have literal value:String implementations
   *
   * Note
   *
   *  - requires the ValueEntryType to have a 'value' member that has a literal value
   */
  def findStringValueEntriesImpl[ValueEntryType: c.WeakTypeTag](c: Context): c.Expr[IndexedSeq[ValueEntryType]] = {
    findValueEntriesImpl[ValueEntryType, String, String](c)(identity)
  }

  /**
   * The method that does the heavy lifting.
   */
  private[this] def findValueEntriesImpl[ValueEntryType: c.WeakTypeTag, ValueType: ClassTag, ProcessedValue](c: Context)(processFoundValues: ValueType => ProcessedValue): c.Expr[IndexedSeq[ValueEntryType]] = {
    import c.universe._
    val typeSymbol = weakTypeOf[ValueEntryType].typeSymbol
    EnumMacros.validateType(c)(typeSymbol)
    // Find the trees in the enclosing object that match the given ValueEntryType
    val subclassTrees = EnumMacros.enclosedSubClassTrees(c)(typeSymbol)
    // Find the parameters for the constructors of ValueEntryType
    val valueEntryTypeConstructorsParams = findConstructorParamsLists[ValueEntryType](c)
    // Identify the value:ValueType implementations for each of the trees we found and process them if required
    val treeWithVals = findValuesForSubclassTrees[ValueType, ProcessedValue](c)(valueEntryTypeConstructorsParams, subclassTrees, processFoundValues)
    // Make sure the processed found value implementations are unique
    ensureUnique[ProcessedValue](c)(treeWithVals)
    // Finish by building our Sequence
    val subclassSymbols = treeWithVals.map(_.tree.symbol)
    EnumMacros.buildSeqExpr[ValueEntryType](c)(subclassSymbols)
  }

  /**
   * Returns a list of TreeWithVal (tree with value of type ProcessedValueType) for the given trees and transformation
   *
   * Will abort compilation if not all the trees provided have a literal value member/constructor argument
   */
  private[this] def findValuesForSubclassTrees[ValueType: ClassTag, ProcessedValueType](c: Context)(valueEntryCTorsParams: List[List[c.universe.Name]], memberTrees: Seq[c.universe.Tree], processFoundValues: ValueType => ProcessedValueType): Seq[TreeWithVal[c.universe.Tree, ProcessedValueType]] = {
    val treeWithValues = toTreeWithMaybeVals[ValueType, ProcessedValueType](c)(valueEntryCTorsParams, memberTrees, processFoundValues)
    val (hasValueMember, lacksValueMember) = treeWithValues.partition(_.maybeValue.isDefined)
    if (lacksValueMember.nonEmpty) {
      val classTag = implicitly[ClassTag[ValueType]]
      val lacksValueMemberStr = lacksValueMember.map(_.tree.symbol).mkString(", ")
      c.abort(
        c.enclosingPosition,
        s"""
           |It looks like not all of the members have a literal/constant 'value:${classTag.runtimeClass.getSimpleName}' declaration, namely: $lacksValueMemberStr.
           |
           |This can happen if:
           |
           |- The aforementioned members have their `value` supplied by a variable, or otherwise defined as a method
           |
           |If none of the above apply to your case, it's likely you have discovered an issue with Enumeratum, so please file an issue :)
         """.stripMargin
      )
    }
    hasValueMember.collect {
      case TreeWithMaybeVal(tree, Some(v)) => TreeWithVal(tree, v)
    }
  }

  /**
   * Looks through the given trees and tries to find the proper value declaration/constructor argument.
   *
   * Aborts compilation if the value declaration/constructor is of the wrong type,
   */
  private[this] def toTreeWithMaybeVals[ValueType: ClassTag, ProcessedValueType](c: Context)(valueEntryCTorsParams: List[List[c.universe.Name]], memberTrees: Seq[c.universe.Tree], processFoundValues: ValueType => ProcessedValueType): Seq[TreeWithMaybeVal[c.universe.Tree, ProcessedValueType]] = {
    import c.universe._
    val classTag = implicitly[ClassTag[ValueType]]
    val valueTerm = ContextUtils.termName(c)("value")
    // go through all the trees
    memberTrees.map { declTree =>
      val values = declTree.collect {
        // The tree has a value declaration with a constant value.
        case ValDef(_, termName, _, Literal(Constant(i: ValueType))) if termName == valueTerm => Some(i)
        // The tree has a method call
        case Apply(fun, args) => {
          val valueArguments: List[Option[ValueType]] = valueEntryCTorsParams.collect {
            // Find the constructor params list that matches the arguments list size of the method call
            case paramTermNames if paramTermNames.size == args.size => {
              val paramsWithArg = paramTermNames.zip(args)
              paramsWithArg.collectFirst {
                // found a (paramName, argument) parameter-argument pair where paramName is "value", and argument is a constant with the right type
                case (`valueTerm`, Literal(Constant(i: ValueType))) => i
                // found a (paramName, argument) parameter-argument pair where paramName is "value", and argument is a constant with the wrong type
                case (`valueTerm`, Literal(Constant(i))) => c.abort(c.enclosingPosition, s"${declTree.symbol} has a value with the wrong type: $i:${i.getClass}, instead of ${classTag.runtimeClass}.")
                /*
                 * found a (_, NamedArgument(argName, argument)) parameter-named pair where the argument is named "value" and the argument itself is of the right type
                 */
                case (_, AssignOrNamedArg(Ident(`valueTerm`), Literal(Constant(i: ValueType)))) => i
                /*
                 * found a (_, NamedArgument(argName, argument)) parameter-named pair where the argument is named "value" and the argument itself is of the wrong type
                 */
                case (_, AssignOrNamedArg(Ident(`valueTerm`), Literal(Constant(i)))) => c.abort(c.enclosingPosition, s"${declTree.symbol} has a value with the wrong type: $i:${i.getClass}, instead of ${classTag.runtimeClass}")
              }
            }
          }
          // We only want the first such constructor argument
          valueArguments.collectFirst { case Some(v) => v }
        }
      }
      val processedValue = values.collectFirst { case Some(v) => processFoundValues(v) }
      TreeWithMaybeVal(declTree, processedValue)
    }
  }

  /**
   * Given a type, finds the constructor params lists for it
   */
  private[this] def findConstructorParamsLists[ValueEntryType: c.WeakTypeTag](c: Context): List[List[c.universe.Name]] = {
    val valueEntryTypeTpe = implicitly[c.WeakTypeTag[ValueEntryType]].tpe
    val valueEntryTypeTpeMembers = valueEntryTypeTpe.members
    valueEntryTypeTpeMembers
      .collect(ContextUtils.constructorsToParamNamesPF(c))
      .toList
  }

  /**
   * Ensures that we have unique values for trees, aborting otherwise with a message indicating which trees have the same symbol
   */
  private[this] def ensureUnique[A](c: Context)(treeWithVals: Seq[TreeWithVal[c.universe.Tree, A]]): Unit = {
    val membersWithValues = treeWithVals.map { treeWithVal =>
      treeWithVal.tree.symbol -> treeWithVal.value
    }
    val groupedByValue = membersWithValues.groupBy(_._2).mapValues(_.map(_._1))
    val (valuesWithOneSymbol, valuesWithMoreThanOneSymbol) = groupedByValue.partition(_._2.size <= 1)
    if (valuesWithOneSymbol.size != membersWithValues.toMap.keys.size) {
      c.abort(
        c.enclosingPosition,
        s"It does not look like you have unique values. Each of the following values correspond to more than one member: $valuesWithMoreThanOneSymbol"
      )
    }
  }

  // Helper case classes
  private[this] case class TreeWithMaybeVal[CTree, T](tree: CTree, maybeValue: Option[T])
  private[this] case class TreeWithVal[CTree, T](tree: CTree, value: T)

}