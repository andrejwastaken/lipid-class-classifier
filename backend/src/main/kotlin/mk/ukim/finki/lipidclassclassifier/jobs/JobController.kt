package mk.ukim.finki.lipidclassclassifier.jobs

import mk.ukim.finki.lipidclassclassifier.auth.UserPrincipal
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/jobs")
class JobController(
    private val jobService: JobService,
) {
    @PostMapping("/upload")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun upload(
        @AuthenticationPrincipal principal: UserPrincipal,
        @RequestParam("file") file: MultipartFile,
    ): UploadResponse = jobService.createUploadJob(principal.id, file)

    @GetMapping("/{jobId}")
    fun getJob(
        @AuthenticationPrincipal principal: UserPrincipal,
        @PathVariable jobId: UUID,
    ): JobResponse = jobService.getJob(principal.id, jobId)
}
