import {Context} from '@/types/context'
import {CopyMode} from '@/types/enum-books'
import { SeriesDto } from './komga-series'

export interface BookDto {
  id: string,
  seriesId: string,
  libraryId: string,
  name: string,
  url: string,
  number: number,
  lastModified: string,
  sizeBytes: number,
  size: string,
  media: MediaDto,
  metadata: BookMetadataDto,
  readProgress?: ReadProgressDto,
  deleted: boolean,

  // custom fields
  context: Context
  series?: SeriesDto
}

export interface MediaDto {
  status: string,
  mediaType: string,
  pagesCount: number,
  comment: string
}

export interface PageDto {
  number: number,
  fileName: string,
  mediaType: string,
  width?: number,
  height?: number,
}

export interface PageDtoWithUrl {
  number: number,
  fileName: string,
  mediaType: string,
  width?: number,
  height?: number,
  url: string,
}

export interface BookMetadataDto {
  created: string,
  lastModified: string,
  title: string,
  titleLock: boolean,
  summary: string,
  summaryLock: boolean,
  number: string,
  numberLock: boolean,
  numberSort: number,
  numberSortLock: boolean,
  releaseDate: string,
  releaseDateLock: boolean,
  authors: AuthorDto[],
  authorsLock: boolean,
  tags: string[],
  tagsLock: boolean,
  isbn: string,
  isbnLock: boolean
}

export interface ReadProgressDto {
  page: number,
  completed: boolean,
  created: string,
  lastModified: string
}

export interface BookMetadataUpdateDto {
  title?: string,
  titleLock?: boolean,
  summary?: string,
  summaryLock?: boolean,
  number?: string,
  numberLock?: boolean,
  numberSort?: number,
  numberSortLock?: boolean,
  releaseDate?: string,
  releaseDateLock?: boolean,
  authors?: AuthorDto[],
  authorsLock?: boolean,
  tags?: string[],
  tagsLock?: boolean
  isbn?: string,
  isbnLock?: boolean
}

export interface BookMetadataUpdateBatchDto {
  [bookId: string]: BookMetadataUpdateBatchDto
}

export interface AuthorDto {
  name: string,
  role: string
}

export interface ReadProgressUpdateDto {
  page?: number,
  completed?: boolean
}

export interface BookFormat {
  type: string,
  color: string
}

export interface BookImportBatchDto{
  books: BookImportDto[],
  copyMode: CopyMode,
}

export interface BookImportDto {
  sourceFile: string,
  seriesId: string,
  upgradeBookId?: string,
  destinationName?: string,
}
