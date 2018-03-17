/**
 * Copyright © 2018 Reijhanniel Jearl Campos (devcsrj@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package devcsrj.maven

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.funktionale.memoization.memoize
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import pl.droidsonroids.retrofit2.JspoonConverterFactory
import retrofit2.Retrofit
import java.time.ZoneId
import java.util.Optional

internal class ScrapingMvnRepositoryApi(
    private val baseUrl: HttpUrl,
    private val okHttpClient: OkHttpClient) : MvnRepositoryApi {

    private val logger: Logger = LoggerFactory.getLogger(MvnRepositoryApi::class.java)
    private val pageApi: MvnRepositoryPageApi

    private val repositories: () -> List<Repository>

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(JspoonConverterFactory.create())
            .validateEagerly(true)
            .build()
        pageApi = retrofit.create(MvnRepositoryPageApi::class.java)

        // Repositories are unlikely to change, so we memoize them instead of fetching them every time
        repositories = {
            var p = 1
            val repos = mutableListOf<Repository>()
            while (true) { // there are only 2(?) pages, we'll query them all at once now
                val response = pageApi.getRepositoriesPage(p).execute()
                p++ // next page
                if (!response.isSuccessful) {
                    logger.warn("Request to $baseUrl failed while fetching repositories, got: ${response.code()}")
                    break
                }
                val page = response.body() ?: break;
                if (page.entries.isEmpty())
                    break // stop when the page no longer shows an entry (we exceeded max page)

                repos.addAll(page.entries.map { Repository(it.id, it.name, it.uri) })
            }
            repos.toList()
        }.memoize()
    }

    override fun getRepositories(): List<Repository> = repositories()

    override fun getArtifact(groupId: String, artifactId: String, version: String): Optional<Artifact> {
        val response = pageApi.getArtifactPage(groupId, artifactId, version).execute()
        if (!response.isSuccessful) {
            logger.warn("Request to $baseUrl failed while fetching artifact '" +
                "$groupId:$artifactId:$version', got: ${response.code()}")
            return Optional.empty()
        }
        val body = response.body() ?: return Optional.empty()
        val localDate = body.date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        val artifact = Artifact(groupId, artifactId, version, body.license, body.homepage, localDate, body.snippets)

        return Optional.of(artifact)
    }
}
