package improving

object Declosurify {
  def mapInfix[A: c0.WeakTypeTag, B: c0.WeakTypeTag, Coll: c0.WeakTypeTag, That: c0.WeakTypeTag](c0: CtxColl[A, Coll])(f0: c0.Expr[A => B]): c0.Expr[That] = {
    val ctx = new MacroSupport[c0.type](c0)
    import ctx._
    import c.universe._

    call.log[A, B, Coll, That]("f0" -> f0.tree)

    def isForeach = weakTypeOf[That] =:= typeOf[Unit]
    def mkFallbackImpl = {
      val name: TermName = if (isForeach) "foreachImpl" else "map"
      val pre = Select(c.prefix.tree, "xs": TermName)

      c.Expr[That](Apply(Select(pre, name), f0.tree :: Nil))
    }

    val flatStats = flatten(f0.tree)
    val fnTree = flatStats.reverse match {
      case (f: Function) :: _ => f
      case _                  => null
    }
    val useFallback = (
         ( fnTree == null)
      || ( c.enclosingMethod == null )
      || ( fnTree exists { case Return(_) => true case _ => false } )
    )
    if (useFallback)
      return mkFallbackImpl

    val closureTree = functionToLocalMethod(fnTree)

    def closure           = closureTree.symbol
    def newBuilder        = weakTypeOf[Coll].typeSymbol.companionSymbol.typeSignature member 'newBuilder
    def closureDef        = c.Expr[Unit](closureTree)
    def builderVal        = c.Expr[Unit](if (isForeach) mkUnit else ValDef(NoMods, 'buf, TypeTree(), newBuilder[B]))
    def mkCall(arg: Tree) = c.Expr[Unit](if (isForeach) closure(arg) else ('buf dot '+=)(closure(arg)))
    def mkResult          = c.Expr[That](if (isForeach) mkUnit else 'buf dot 'result)

    def mkIndexed[Prefix](prefixTree: Tree): c.Expr[That] = {
      val prefix = c.Expr[Prefix](prefixTree)
      val len    = c.Expr[Int]('xs dot 'length) // might be array or indexedseq
      val call   = mkCall('xs('i))

      reify {
        closureDef.splice
        builderVal.splice
        val xs = prefix.splice
        var i  = 0
        while (i < len.splice) {
          call.splice
          i += 1
        }
        mkResult.splice
      }
    }

    def mkLinear(prefixTree: Tree): c.Expr[That] = {
      val prefix    = c.Expr[Lin[A]](prefixTree)
      val call = mkCall('these dot 'head)

      reify {
        closureDef.splice
        builderVal.splice
        var these = prefix.splice
        while (!these.isEmpty) {
          call.splice
          these = these.tail
        }
        mkResult.splice
      }
    }

    def mkTraversable(prefixTree: Tree): c.Expr[That] = {
      val prefix = c.Expr[Traversable[A]](prefixTree)
      val call   = mkCall('it dot 'next)

      reify {
        closureDef.splice
        builderVal.splice
        val it = prefix.splice.toIterator
        while (it.hasNext)
          call.splice

        mkResult.splice
      }
    }

    val resExpr = c.prefix match {
      case ArrayPrefix(tree)       => mkIndexed[Array[A]](tree)
      case IndexedPrefix(tree)     => mkIndexed[Ind[A]](tree)
      case LinearPrefix(tree)      => mkLinear(tree)
      case TraversablePrefix(tree) => mkTraversable(tree)
      case _                       => mkFallbackImpl
    }

    flatStats.init match {
      case Nil   => resExpr
      case stats => c.Expr[That](Block(stats, resExpr.tree))
    }
  }

  def foreachInfix[A: c0.WeakTypeTag, Coll: c0.WeakTypeTag](c0: CtxColl[A, Coll])(f0: c0.Expr[A => Any]): c0.Expr[Unit] =
    mapInfix[A, Any, Coll, Unit](c0)(f0)
}
