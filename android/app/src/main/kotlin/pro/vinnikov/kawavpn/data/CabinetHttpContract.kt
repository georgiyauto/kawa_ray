package pro.vinnikov.kawavpn.data
internal fun checkCabinetStatus(status:Int) {
    if(status==401) throw LoginExpired()
    if(status !in 200..299) throw ApiUnavailable(status)
}
