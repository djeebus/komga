<template>
  <v-hover :disabled="disableHover">
    <template>
      <v-card
        @click="onClick"
        :class="noLink ? 'no-link' : ''"
        :ripple="false"
      >
        <!--      Description-->
        <router-link :to="to" class="link-underline">
          <v-card-subtitle
            v-line-clamp="2"
            v-bind="subtitleProps"
            v-html="title"
          >
          </v-card-subtitle>
        </router-link>
      </v-card>
    </template>
  </v-hover>
</template>

<script lang="ts">
import BookActionsMenu from '@/components/menus/BookActionsMenu.vue'
import CollectionActionsMenu from '@/components/menus/CollectionActionsMenu.vue'
import SeriesActionsMenu from '@/components/menus/SeriesActionsMenu.vue'
import {getReadProgress, getReadProgressPercentage} from '@/functions/book-progress'
import {ReadStatus} from '@/types/enum-books'
import {createItem, Item, ItemTypes} from '@/types/items'
import Vue from 'vue'
import {RawLocation} from 'vue-router'
import ReadListActionsMenu from '@/components/menus/ReadListActionsMenu.vue'
import {BookDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {THUMBNAILBOOK_ADDED, THUMBNAILSERIES_ADDED} from '@/types/events'
import {ThumbnailBookSseDto, ThumbnailSeriesSseDto} from '@/types/komga-sse'
import {coverBase64} from '@/types/image'

export default Vue.extend({
  name: 'ItemRow',
  components: {BookActionsMenu, SeriesActionsMenu, CollectionActionsMenu, ReadListActionsMenu},
  props: {
    item: {
      type: Object as () => BookDto | SeriesDto | CollectionDto | ReadListDto,
      required: true,
    },
    // hide the bottom part of the card
    thumbnailOnly: {
      type: Boolean,
      default: false,
    },
    // disables the default link on clicking the card
    noLink: {
      type: Boolean,
      default: false,
    },
    width: {
      type: [String, Number],
      required: false,
      default: 150,
    },
    // when true, card will show the active border and circle icon full
    selected: {
      type: Boolean,
      default: false,
    },
    // when true, will display the border like if the card was hovered, and click anywhere will trigger onSelected
    preselect: {
      type: Boolean,
      required: false,
    },
    // callback function to call when selecting the card
    onSelected: {
      type: Function,
      default: undefined,
      required: false,
    },
    // callback function for the edit button
    onEdit: {
      type: Function,
      default: undefined,
      required: false,
    },
    // action menu enabled or not
    actionMenu: {
      type: Boolean,
      default: true,
    },
  },
  data: () => {
    return {
      ItemTypes,
      actionMenuState: false,
      thumbnailError: false,
      thumbnailCacheBust: '',
      coverBase64,
    }
  },
  created() {
    this.$eventHub.$on(THUMBNAILBOOK_ADDED, this.thumbnailBookAdded)
    this.$eventHub.$on(THUMBNAILSERIES_ADDED, this.thumbnailSeriesAdded)
  },
  beforeDestroy() {
    this.$eventHub.$off(THUMBNAILBOOK_ADDED, this.thumbnailBookAdded)
    this.$eventHub.$off(THUMBNAILSERIES_ADDED, this.thumbnailSeriesAdded)
  },
  computed: {
    canReadPages(): boolean {
      return this.$store.getters.mePageStreaming && this.computedItem.type() === ItemTypes.BOOK
    },
    overlay(): boolean {
      return this.onEdit !== undefined || this.onSelected !== undefined || this.bookReady || this.canReadPages || this.actionMenu
    },
    computedItem(): Item<BookDto | SeriesDto | CollectionDto | ReadListDto> {
      let item = this.item
      if ('libraryId' in this.item && this.$store.getters.getLibraryById((this.item as any).libraryId).unavailable)
        item = {...item, deleted: true}
      return createItem(item)
    },
    disableHover(): boolean {
      return !this.overlay
    },
    title(): string {
      return this.computedItem.title()
    },
    subtitleProps(): Object {
    return {
      style: 'word-break: normal !important; height: 3em; margin-left: 1em; margin-right: 4em',
      'class': 'pa-2 pb-1 text--primary',
      title: this.computedItem.title(),
    }
    },
    body(): string {
      return this.computedItem.body()
    },
    isInProgress(): boolean {
      if (this.computedItem.type() === ItemTypes.BOOK) return getReadProgress(this.item as BookDto) === ReadStatus.IN_PROGRESS
      return false
    },
    isUnread(): boolean {
      if (this.computedItem.type() === ItemTypes.BOOK) return getReadProgress(this.item as BookDto) === ReadStatus.UNREAD
      return false
    },
    unreadCount(): number | undefined {
      if (this.computedItem.type() === ItemTypes.SERIES) return (this.item as SeriesDto).booksUnreadCount + (this.item as SeriesDto).booksInProgressCount
      return undefined
    },
    readProgressPercentage(): number {
      if (this.computedItem.type() === ItemTypes.BOOK) return getReadProgressPercentage(this.item as BookDto)
      return 0
    },
    bookReady(): boolean {
      if (this.computedItem.type() === ItemTypes.BOOK) {
        return (this.item as BookDto).media.status === 'READY'
      }
      return false
    },
    to(): RawLocation {
      return this.computedItem.to()
    },
    fabTo(): RawLocation {
      return this.computedItem.fabTo()
    },
  },
  methods: {
    thumbnailBookAdded(event: ThumbnailBookSseDto) {
      if (this.thumbnailError &&
        ((this.computedItem.type() === ItemTypes.BOOK && event.bookId === this.item.id) || (this.computedItem.type() === ItemTypes.SERIES && event.seriesId === this.item.id))
      ) {
        this.thumbnailCacheBust = '?' + this.$_.random(1000)
      }
    },
    thumbnailSeriesAdded(event: ThumbnailSeriesSseDto) {
      if (this.computedItem.type() === ItemTypes.SERIES && event.seriesId === this.item.id) {
        this.thumbnailCacheBust = '?' + this.$_.random(1000)
      }
    },
    onClick(e: MouseEvent) {
      if (this.preselect && this.onSelected !== undefined) {
        this.selectItem(e)
      } else if (!this.noLink) {
        this.goto()
      }
    },
    goto() {
      this.$router.push(this.computedItem.to())
    },
    selectItem(e: MouseEvent) {
      if (this.onSelected !== undefined) {
        this.onSelected(this.item, e)
      }
    },
    editItem() {
      if (this.onEdit !== undefined) {
        this.onEdit(this.item)
      }
    },
  },
})
</script>

<style>
.no-link {
  cursor: default;
}

.item-border {
  border: 3px solid var(--v-secondary-base);
}

.item-border-transparent {
  border: 3px solid transparent;
}

.item-border-darken {
  border: 3px solid var(--v-secondary-darken2);
}

.overlay-full .v-overlay__content {
  width: 100px;
  height: 100px;
}

.unread {
  border-left: 25px solid transparent;
  border-right: 25px solid orange;
  border-bottom: 25px solid transparent;
  height: 0;
  width: 0;
  position: absolute;
  right: 0;
  z-index: 2;
}
</style>
