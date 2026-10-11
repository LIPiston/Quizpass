package top.lipiston.quizpass.data

import top.lipiston.quizpass.data.type.ServerToClientRequestType

data class RconData(
    val requestId: Int,
    val requestType: ServerToClientRequestType,
    val payload: String
) {

}