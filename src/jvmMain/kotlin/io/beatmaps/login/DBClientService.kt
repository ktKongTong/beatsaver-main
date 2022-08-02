package io.beatmaps.login

import io.beatmaps.common.dbo.OauthClient
import io.beatmaps.common.dbo.OauthClientDao
import nl.myndocs.oauth2.client.AuthorizedGrantType
import nl.myndocs.oauth2.client.Client
import nl.myndocs.oauth2.client.ClientService
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object DBClientService : ClientService {
    fun getClient(clientId: String) = transaction {
        OauthClient.select { OauthClient.clientId eq clientId }.firstOrNull()?.let { client -> OauthClientDao.wrapRow(client) }
    }

    override fun clientOf(clientId: String) = getClient(clientId)?.let { client ->
        Client(
            client.clientId,
            client.scopes?.split(",")?.toSet() ?: emptySet(),
            setOfNotNull(client.redirectUrl),
            setOf(
                AuthorizedGrantType.AUTHORIZATION_CODE,
                AuthorizedGrantType.REFRESH_TOKEN
            )
        )
    }

    override fun validClient(client: Client, clientSecret: String): Boolean {
        return transaction {
            !OauthClient.select { (OauthClient.clientId eq client.clientId) and (OauthClient.secret eq clientSecret) }.empty()
        }
    }
}
