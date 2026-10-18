package kc.ac.uc.speechbridge

data class PcmSamples(val rate: Int, val samples: ShortArray)
object PcmWave {
    fun encode(samples: ShortArray, rate: Int): ByteArray {
        require(rate in setOf(16000,24000) && samples.isNotEmpty() && samples.size <= rate*30)
        val bytes=ByteArray(44+samples.size*2)
        fun ascii(offset:Int,value:String) {value.encodeToByteArray().copyInto(bytes,offset)}
        fun number(offset:Int,value:Int,size:Int=4) {repeat(size){bytes[offset+it]=(value ushr (it*8)).toByte()}}
        ascii(0,"RIFF");number(4,bytes.size-8);ascii(8,"WAVEfmt ");number(16,16);number(20,1,2);number(22,1,2)
        number(24,rate);number(28,rate*2);number(32,2,2);number(34,16,2);ascii(36,"data");number(40,samples.size*2)
        samples.forEachIndexed {i,value->number(44+i*2,value.toInt(),2)}
        return bytes
    }
    fun decode(bytes:ByteArray, rate:Int, allowMetadata:Boolean=false):PcmSamples {
        require(rate in setOf(16000,24000) && bytes.size in 46..(rate*60+65580))
        fun number(offset:Int,size:Int=4):Int {require(offset+size<=bytes.size);return (0 until size).fold(0){v,i->v or ((bytes[offset+i].toInt() and 255) shl (i*8))}}
        fun ascii(offset:Int)=bytes.copyOfRange(offset,offset+4).decodeToString()
        require(ascii(0)=="RIFF" && number(4)==bytes.size-8 && ascii(8)=="WAVE")
        var offset=12;var format=false;var pcm:ShortArray?=null
        while(offset+8<=bytes.size) {
            val name=ascii(offset);val size=number(offset+4);val start=offset+8
            require(size>=0 && size<=bytes.size-start)
            when(name) {
                "fmt " -> {
                    require(!format && size>=16 && (allowMetadata || size==16))
                    require(number(start,2)==1 && number(start+2,2)==1 && number(start+4)==rate && number(start+8)==rate*2 && number(start+12,2)==2 && number(start+14,2)==16)
                    format=true
                }
                "data" -> {
                    require(format && pcm==null && size>0 && size%2==0 && size<=rate*60)
                    pcm=ShortArray(size/2){number(start+it*2,2).toShort()}
                }
                else -> require(allowMetadata)
            }
            offset=start+size+(size%2)
        }
        require(offset==bytes.size && format && pcm!=null)
        if(!allowMetadata) require(bytes.size==44+pcm.size*2)
        return PcmSamples(rate,pcm)
    }
}
