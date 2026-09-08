package net.rokoucha.visiomata

import net.rokoucha.visiomata.mahiron.infrastructure.ClientException
import net.rokoucha.visiomata.mahiron.infrastructure.ServerException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class ConnectionErrorMessageTest {
    @Test
    fun authenticationErrorSuggestsCheckingCredentials() {
        assertEquals(
            "認証に失敗しました。認証方式と認証情報を確認してください",
            connectionErrorMessage(ClientException(statusCode = 401)),
        )
    }

    @Test
    fun missingApiSuggestsCheckingUrl() {
        assertEquals(
            "Mirakurun APIが見つかりません。接続先URLを確認してください",
            connectionErrorMessage(ClientException(statusCode = 404)),
        )
    }

    @Test
    fun networkErrorsDoNotExposeExceptionMessages() {
        assertEquals(
            "サーバーが見つかりません。ホスト名やネットワークを確認してください",
            connectionErrorMessage(UnknownHostException("internal exception detail")),
        )
        assertEquals(
            "サーバーに接続できません。URLとサーバーの起動状態を確認してください",
            connectionErrorMessage(ConnectException("internal exception detail")),
        )
    }

    @Test
    fun serverErrorIncludesOnlyUsefulStatusCode() {
        assertEquals(
            "サーバーでエラーが発生しました（HTTP 503）",
            connectionErrorMessage(ServerException("response body", statusCode = 503)),
        )
    }
}
