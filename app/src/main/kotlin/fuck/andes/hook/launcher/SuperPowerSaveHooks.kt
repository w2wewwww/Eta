    private fun restore(list:MutableList<Any?>,owner:Any,cl:ClassLoader){
        if(list.size>=MAX)return
        val c=HookSupport.getFieldValue(owner,"mContext")?:return
        val w=HookSupport.findClassOrNull(cl,W)?:return
        val f=HookSupport.findMethod(w,"getItemFromSp",c.javaClass,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        for(i in list.size until MAX)f.invoke(null,c,i%COLS,i/COLS)?.let{list.add(it)}
    }
    private fun append(owner:Any,apps:Any?,output:Any?,cl:ClassLoader){
        val list=output as? MutableList<Any?>?:return;if(list.size>=MAX)return
        val a=apps as? java.util.ArrayList<*>?:return;val c=HookSupport.getFieldValue(owner,"mContext")?:return
        val w=HookSupport.findClassOrNull(cl,W)?:return
        val get=HookSupport.findMethod(w,"getItemFromSp",c.javaClass,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        val tip=HookSupport.findMethod(owner.javaClass,"buildTipIconItem",Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        val match=HookSupport.findDeclaredMethods(owner.javaClass,true){it.name=="findMatchItem"&&it.parameterTypes.size==2}.firstOrNull()?:return
        for(i in list.size until MAX){val x=i%COLS;val y=i/COLS;val model=get.invoke(null,c,x,y);list.add(if(model!=null)match.invoke(owner,model,a)?:tip.invoke(owner,x,y) else tip.invoke(owner,x,y))}
    }
}
    private fun restore(list:MutableList<Any?>,owner:Any,cl:ClassLoader) {
        if(list.size>=MAX)return
        val context=HookSupport.getFieldValue(owner,"mContext")?:return
        val writer=HookSupport.findClassOrNull(cl,WRITER)?:return
        val get=HookSupport.findMethod(writer,"getItemFromSp",context.javaClass,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        for(i in list.size until MAX)get.invoke(null,context,i%COLS,i/COLS)?.let{list.add(it)}
    }
    private fun append(owner:Any,apps:Any?,output:Any?,cl:ClassLoader) {
        val list=output as? MutableList<Any?>?:return
        if(list.size>=MAX)return
        val appList=apps as? java.util.ArrayList<*>?:return
        val context=HookSupport.getFieldValue(owner,"mContext")?:return
        val writer=HookSupport.findClassOrNull(cl,WRITER)?:return
        val get=HookSupport.findMethod(writer,"getItemFromSp",context.javaClass,Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        val tip=HookSupport.findMethod(owner.javaClass,"buildTipIconItem",Int::class.javaPrimitiveType!!,Int::class.javaPrimitiveType!!)?:return
        val match=HookSupport.findDeclaredMethods(owner.javaClass,true){it.name=="findMatchItem"&&it.parameterTypes.size==2}.firstOrNull()?:return
        for(i in list.size until MAX){
            val x=i%COLS;val y=i/COLS;val model=get.invoke(null,context,x,y)
            list.add(if(model!=null)match.invoke(owner,model,appList)?:tip.invoke(owner,x,y) else tip.invoke(owner,x,y))
        }
    }
}
