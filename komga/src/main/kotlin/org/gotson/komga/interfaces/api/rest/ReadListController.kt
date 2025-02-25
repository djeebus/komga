package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import mu.KotlinLogging
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.io.IOUtils
import org.gotson.komga.application.events.EventPublisher
import org.gotson.komga.domain.model.Author
import org.gotson.komga.domain.model.BookSearchWithReadProgress
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.DuplicateNameException
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaType.ZIP
import org.gotson.komga.domain.model.ROLE_ADMIN
import org.gotson.komga.domain.model.ROLE_FILE_DOWNLOAD
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.ReadStatus
import org.gotson.komga.domain.model.ThumbnailReadList
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ThumbnailReadListRepository
import org.gotson.komga.domain.service.BookLifecycle
import org.gotson.komga.domain.service.ReadListLifecycle
import org.gotson.komga.infrastructure.jooq.UnpagedSorted
import org.gotson.komga.infrastructure.mediacontainer.ContentDetector
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.infrastructure.swagger.AuthorsAsQueryParam
import org.gotson.komga.infrastructure.swagger.PageableWithoutSortAsQueryParam
import org.gotson.komga.infrastructure.web.Authors
import org.gotson.komga.interfaces.api.persistence.BookDtoRepository
import org.gotson.komga.interfaces.api.persistence.ReadProgressDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.BookDto
import org.gotson.komga.interfaces.api.rest.dto.ReadListCreationDto
import org.gotson.komga.interfaces.api.rest.dto.ReadListDto
import org.gotson.komga.interfaces.api.rest.dto.ReadListRequestResultDto
import org.gotson.komga.interfaces.api.rest.dto.ReadListUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.TachiyomiReadProgressDto
import org.gotson.komga.interfaces.api.rest.dto.TachiyomiReadProgressUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.ThumbnailReadListDto
import org.gotson.komga.interfaces.api.rest.dto.restrictUrl
import org.gotson.komga.interfaces.api.rest.dto.toDto
import org.gotson.komga.language.toIndexedMap
import org.springframework.core.io.FileSystemResource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import javax.validation.Valid

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/readlists", produces = [MediaType.APPLICATION_JSON_VALUE])
class ReadListController(
  private val readListRepository: ReadListRepository,
  private val readListLifecycle: ReadListLifecycle,
  private val bookDtoRepository: BookDtoRepository,
  private val bookRepository: BookRepository,
  private val readProgressDtoRepository: ReadProgressDtoRepository,
  private val thumbnailReadListRepository: ThumbnailReadListRepository,
  private val contentDetector: ContentDetector,
  private val bookLifecycle: BookLifecycle,
  private val eventPublisher: EventPublisher,
) {

  @PageableWithoutSortAsQueryParam
  @GetMapping
  fun getAll(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "search", required = false) searchTerm: String?,
    @RequestParam(name = "library_id", required = false) libraryIds: List<String>?,
    @RequestParam(name = "unpaged", required = false) unpaged: Boolean = false,
    @Parameter(hidden = true) page: Pageable,
  ): Page<ReadListDto> {
    val sort = when {
      !searchTerm.isNullOrBlank() -> Sort.by("relevance")
      else -> Sort.by(Sort.Order.asc("name"))
    }

    val pageRequest =
      if (unpaged) UnpagedSorted(sort)
      else PageRequest.of(
        page.pageNumber,
        page.pageSize,
        sort,
      )

    return readListRepository.findAll(principal.user.getAuthorizedLibraryIds(libraryIds), principal.user.getAuthorizedLibraryIds(null), searchTerm, pageRequest, principal.user.restrictions)
      .map { it.toDto() }
  }

  @GetMapping("{id}")
  fun getOne(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable id: String,
  ): ReadListDto =
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)
      ?.toDto()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @ApiResponse(content = [Content(schema = Schema(type = "string", format = "binary"))])
  @GetMapping(value = ["{id}/thumbnail"], produces = [MediaType.IMAGE_JPEG_VALUE])
  fun getReadListThumbnail(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable id: String,
  ): ResponseEntity<ByteArray> {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let {
      return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
        .body(readListLifecycle.getThumbnailBytes(it))
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @ApiResponse(content = [Content(schema = Schema(type = "string", format = "binary"))])
  @GetMapping(value = ["{id}/thumbnails/{thumbnailId}"], produces = [MediaType.IMAGE_JPEG_VALUE])
  fun getReadListThumbnailById(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable(name = "id") id: String,
    @PathVariable(name = "thumbnailId") thumbnailId: String,
  ): ByteArray {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let {
      return readListLifecycle.getThumbnailBytes(thumbnailId)
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @GetMapping(value = ["{id}/thumbnails"], produces = [MediaType.APPLICATION_JSON_VALUE])
  fun getReadListThumbnails(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable(name = "id") id: String,
  ): Collection<ThumbnailReadListDto> {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let {
      return thumbnailReadListRepository.findAllByReadListId(id).map { it.toDto() }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PostMapping(value = ["{id}/thumbnails"], consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  fun addUserUploadedReadListThumbnail(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable(name = "id") id: String,
    @RequestParam("file") file: MultipartFile,
    @RequestParam("selected") selected: Boolean = true,
  ): ThumbnailReadListDto {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let { readList ->

      if (!contentDetector.isImage(file.inputStream.buffered().use { contentDetector.detectMediaType(it) }))
        throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)

      return readListLifecycle.addThumbnail(
        ThumbnailReadList(
          readListId = readList.id,
          thumbnail = file.bytes,
          type = ThumbnailReadList.Type.USER_UPLOADED,
          selected = selected,
        ),
      ).toDto()
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PutMapping("{id}/thumbnails/{thumbnailId}/selected")
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun markSelectedReadListThumbnail(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable(name = "id") id: String,
    @PathVariable(name = "thumbnailId") thumbnailId: String,
  ) {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let {
      thumbnailReadListRepository.findByIdOrNull(thumbnailId)?.let {
        readListLifecycle.markSelectedThumbnail(it)
        eventPublisher.publishEvent(DomainEvent.ThumbnailReadListAdded(it.copy(selected = true)))
      }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @DeleteMapping("{id}/thumbnails/{thumbnailId}")
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun deleteUserUploadedReadListThumbnail(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable(name = "id") id: String,
    @PathVariable(name = "thumbnailId") thumbnailId: String,
  ) {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null), principal.user.restrictions)?.let {
      thumbnailReadListRepository.findByIdOrNull(thumbnailId)?.let {
        readListLifecycle.deleteThumbnail(it)
      } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PostMapping
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  fun addOne(
    @Valid @RequestBody readList: ReadListCreationDto,
  ): ReadListDto =
    try {
      readListLifecycle.addReadList(
        ReadList(
          name = readList.name,
          summary = readList.summary,
          bookIds = readList.bookIds.toIndexedMap(),
        ),
      ).toDto()
    } catch (e: DuplicateNameException) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
    }

  @PostMapping("/import")
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  fun importFromComicRackList(
    @RequestParam("files") files: List<MultipartFile>,
  ): List<ReadListRequestResultDto> =
    files.map { readListLifecycle.importReadList(it.bytes).toDto(it.originalFilename) }

  @PatchMapping("{id}")
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun updateOne(
    @PathVariable id: String,
    @Valid @RequestBody readList: ReadListUpdateDto,
  ) {
    readListRepository.findByIdOrNull(id)?.let { existing ->
      val updated = existing.copy(
        name = readList.name ?: existing.name,
        summary = readList.summary ?: existing.summary,
        bookIds = readList.bookIds?.toIndexedMap() ?: existing.bookIds,
      )
      try {
        readListLifecycle.updateReadList(updated)
      } catch (e: DuplicateNameException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
      }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @DeleteMapping("{id}")
  @PreAuthorize("hasRole('$ROLE_ADMIN')")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun deleteOne(
    @PathVariable id: String,
  ) {
    readListRepository.findByIdOrNull(id)?.let {
      readListLifecycle.deleteReadList(it)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PageableWithoutSortAsQueryParam
  @AuthorsAsQueryParam
  @GetMapping("{id}/books")
  fun getBooksForReadList(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "library_id", required = false) libraryIds: List<String>?,
    @RequestParam(name = "read_status", required = false) readStatus: List<ReadStatus>?,
    @RequestParam(name = "tag", required = false) tags: List<String>?,
    @RequestParam(name = "media_status", required = false) mediaStatus: List<Media.Status>?,
    @RequestParam(name = "deleted", required = false) deleted: Boolean?,
    @RequestParam(name = "unpaged", required = false) unpaged: Boolean = false,
    @Parameter(hidden = true) @Authors authors: List<Author>?,
    @Parameter(hidden = true) page: Pageable,
  ): Page<BookDto> =
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let { readList ->
      val sort = Sort.by(Sort.Order.asc("readList.number"))

      val pageRequest =
        if (unpaged) UnpagedSorted(sort)
        else PageRequest.of(
          page.pageNumber,
          page.pageSize,
          sort,
        )

      val bookSearch = BookSearchWithReadProgress(
        libraryIds = principal.user.getAuthorizedLibraryIds(libraryIds),
        readStatus = readStatus,
        mediaStatus = mediaStatus,
        deleted = deleted,
        tags = tags,
        authors = authors,
      )

      bookDtoRepository.findAllByReadListId(
        readList.id,
        principal.user.id,
        principal.user.getAuthorizedLibraryIds(null),
        bookSearch,
        pageRequest,
      )
        .map { it.restrictUrl(!principal.user.roleAdmin) }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{id}/books/{bookId}/previous")
  fun getBookSiblingPrevious(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable id: String,
    @PathVariable bookId: String,
  ): BookDto =
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let {
      bookDtoRepository.findPreviousInReadListOrNull(
        id,
        bookId,
        principal.user.id,
        principal.user.getAuthorizedLibraryIds(null),
      )
        ?.restrictUrl(!principal.user.roleAdmin)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{id}/books/{bookId}/next")
  fun getBookSiblingNext(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable id: String,
    @PathVariable bookId: String,
  ): BookDto =
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let {
      bookDtoRepository.findNextInReadListOrNull(
        id,
        bookId,
        principal.user.id,
        principal.user.getAuthorizedLibraryIds(null),
      )
        ?.restrictUrl(!principal.user.roleAdmin)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("{id}/read-progress/tachiyomi")
  fun getReadProgress(
    @PathVariable id: String,
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ): TachiyomiReadProgressDto =
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let { readList ->
      readProgressDtoRepository.findProgressByReadList(readList.id, principal.user.id)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @PutMapping("{id}/read-progress/tachiyomi")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun markReadProgressTachiyomi(
    @PathVariable id: String,
    @Valid @RequestBody readProgress: TachiyomiReadProgressUpdateDto,
    @AuthenticationPrincipal principal: KomgaPrincipal,
  ) {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let { readList ->
      bookDtoRepository.findAllByReadListId(
        readList.id,
        principal.user.id,
        principal.user.getAuthorizedLibraryIds(null),
        BookSearchWithReadProgress(),
        UnpagedSorted(Sort.by(Sort.Order.asc("readList.number"))),
      ).filterIndexed { index, _ -> index < readProgress.lastBookRead }
        .forEach { book ->
          if (book.readProgress?.completed != true)
            bookLifecycle.markReadProgressCompleted(book.id, principal.user)
        }
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @GetMapping("{id}/file", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
  @PreAuthorize("hasRole('$ROLE_FILE_DOWNLOAD')")
  fun getReadListFile(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable id: String,
  ): ResponseEntity<StreamingResponseBody> {
    readListRepository.findByIdOrNull(id, principal.user.getAuthorizedLibraryIds(null))?.let { readList ->

      val books = readList.bookIds
        .mapNotNull { bookRepository.findByIdOrNull(it.value)?.let { book -> it.key to book } }
        .toMap()

      val streamingResponse = StreamingResponseBody { responseStream: OutputStream ->
        ZipArchiveOutputStream(responseStream).use { zipStream ->
          zipStream.setMethod(ZipArchiveOutputStream.DEFLATED)
          zipStream.setLevel(Deflater.NO_COMPRESSION)
          books.forEach { (index, book) ->
            val file = FileSystemResource(book.path)
            if (!file.exists()) {
              logger.warn { "Book file not found, skipping archive entry: ${file.path}" }
              return@forEach
            }

            logger.debug { "Adding file to zip archive: ${file.path}" }
            file.inputStream.use {
              zipStream.putArchiveEntry(ZipArchiveEntry("${index + 1} - ${file.filename}"))
              IOUtils.copyLarge(it, zipStream, ByteArray(8192))
              zipStream.closeArchiveEntry()
            }
          }
        }
      }

      return ResponseEntity.ok()
        .headers(
          HttpHeaders().apply {
            contentDisposition = ContentDisposition.builder("attachment")
              .filename(readList.name + ".zip")
              .build()
          },
        )
        .contentType(MediaType.parseMediaType(ZIP.value))
        .body(streamingResponse)
    } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }
}
