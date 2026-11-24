package com.poria.netnsd.game

import com.poria.netnsd.link.TcpHandler

class AzulChannel : TcpHandler {
    override val ns: String = NS

    companion object {
        const val NS = "azul"
    }
}