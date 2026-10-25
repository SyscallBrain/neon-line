package pt.tiago.neonline.leveltool

import java.io.File
import java.util.Random
import kotlinx.serialization.json.Json
import pt.tiago.neonline.core.content.*
import pt.tiago.neonline.core.model.Move
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.solver.BoundedWinSolver

/** Fast candidates are independently certified through GameEngine before publication. */
object RushPool {
    private val json=Json { encodeDefaults=true;prettyPrint=true }
    private val order=intArrayOf(3,2,4,1,5,0,6)
    private fun line(bits:Long):Boolean = intArrayOf(1,7,6,8).any { shift ->
        val pairs=bits and (bits ushr shift); pairs and (pairs ushr (shift*2)) != 0L
    }
    private fun bit(a:Long,b:Long,col:Int):Long {
        val occupied=(a or b) ushr (col*7)
        val height=java.lang.Long.numberOfTrailingZeros(occupied.inv())
        return if(height<6)1L shl (col*7+height) else 0L
    }
    private data class Key(val a:Long,val b:Long,val remaining:Int,val own:Boolean)
    private class Proof {
        private val memo=HashMap<Key,Boolean>()
        fun wins(a:Long,b:Long,remaining:Int,own:Boolean=true):Boolean {
            if(line(a))return true
            if(line(b) || remaining<=0)return false
            val key=Key(a,b,remaining,own)
            memo[key]?.let { return it }
            val available=order.filter { bit(a,b,it)!=0L }
            val answer=if(own)available.any { wins(a or bit(a,b,it),b,remaining-1,false) }
                else available.isNotEmpty() && available.all { wins(a,b or bit(a,b,it),remaining,true) }
            memo[key]=answer
            return answer
        }
    }
    fun generate(count:Int=500,seed:Long=20261016L,version:Int=1):RushPoolDto {
        require(count in 3..500 && version>0)
        val quotas=intArrayOf(count/3+if(count%3>0)1 else 0,count/3+if(count%3>1)1 else 0,count/3)
        val totals=IntArray(3)
        val random=Random(seed)
        val entries=mutableListOf<RushEntryDto>()
        val seen=HashSet<String>()
        for(attempt in 0 until 2_000_000) {
            if(entries.size==count)break
            var a=0L;var b=0L
            val opening=mutableListOf<Int>()
            val target=28+2*(attempt%5)
            repeat(target) {
                val options=order.filter { col -> val bit=bit(a,b,col);bit!=0L && !line(a or bit) }
                if(options.isNotEmpty()) {
                    val col=options[random.nextInt(options.size)]
                    val played=a or bit(a,b,col);a=b;b=played;opening+=col
                }
            }
            if(opening.size!=target)continue
            val proof=Proof()
            val goal=(1..3).firstOrNull { proof.wins(a,b,it) } ?: continue
            if(totals[goal-1]>=quotas[goal-1])continue
            val id="rush-"+(entries.size+1).toString().padStart(4,'0')
            val columns=(0..6).filter { col -> val bit=bit(a,b,col);bit!=0L && proof.wins(a or bit,b,goal-1,false) }
            var entry=RushEntryDto(id,random.nextLong().toULong().toString(16).padStart(16,'0'),opening.toList(),goal,columns,"")
            val state=entry.toRushState(2)
            if(!seen.add(rushPositionKey(state)))continue
            val actual=BoundedWinSolver().winningMoves(state,goal)?.map { (it as Move.Drop).col }?.sorted()
            require(actual==columns && (goal==1 || BoundedWinSolver().canWin(state,goal-1)==false)) { "Candidate proof disagrees with engine: $id" }
            entry=entry.copy(stateSha256=StateHasher.hash(state))
            entries+=entry;totals[goal-1]++
        }
        require(entries.size==count) { "Only generated ${entries.size}/$count puzzles" }
        return RushPoolDto(version,2,seed.toULong().toString(16).padStart(16,'0'),entries).also { verify(it) }
    }
    fun verify(pool:RushPoolDto) {
        val states=validateRushPool(pool)
        for((entry,state) in pool.entries.zip(states)) {
            require(BoundedWinSolver().winningMoves(state,entry.winInMoves)?.map { (it as Move.Drop).col }?.sorted()==entry.winningColumns) { "Invalid winning roots ${entry.id}" }
            require(entry.winInMoves==1 || BoundedWinSolver().canWin(state,entry.winInMoves-1)==false) { "Goal is not minimal ${entry.id}" }
        }
    }
    fun write(output:File,count:Int=500,seed:Long=20261016L,version:Int=1) {
        val pool=generate(count,seed,version)
        if(output.isFile) {
            val old=Json.decodeFromString(RushPoolDto.serializer(),output.readText())
            require(old==pool || version>old.version) { "Changing the rush pool requires a higher version" }
        }
        output.parentFile?.mkdirs()
        val temporary=File(output.path+".tmp")
        try {
            temporary.writeText(json.encodeToString(RushPoolDto.serializer(),pool)+"\n")
            check(temporary.renameTo(output)) { "Cannot publish rush pool" }
        } finally { temporary.delete() }
        println("rush-pool: ${pool.entries.size} distinct certified positions, version $version")
    }
}
