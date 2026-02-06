<template>
  <div class="lineage-flow">
    <VueFlow
      :id="flowId"
      :nodes="nodes"
      :edges="edges"
      :node-types="nodeTypes"
      :default-edge-options="defaultEdgeOptions"
      :nodes-draggable="false"
      :nodes-connectable="false"
      :edges-updatable="false"
      :zoom-on-double-click="false"
      :only-render-visible-elements="true"
      :min-zoom="0.1"
      :max-zoom="2"
      fit-view-on-init
      @init="handleInit"
      @node-click="handleNodeClick"
      @node-mouse-enter="handleNodeMouseEnter"
      @node-mouse-leave="clearHighlight"
      @pane-click="clearHighlight"
    >
      <Background :gap="18" :size="1" pattern-color="#E5E7EB" />
      <MiniMap :node-color="miniMapNodeColor" :mask-color="'rgba(0,0,0,0.06)'" />
      <Controls :show-interactive="false" />
    </VueFlow>
  </div>
</template>

<script setup>
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

import { ref, watch, nextTick, markRaw } from 'vue'
import { VueFlow, MarkerType, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import LineageNode from './nodes/LineageNode.vue'
import {
  LINEAGE_NODE_HEIGHT,
  LINEAGE_NODE_WIDTH,
  buildLineageFlow,
  getLayerColor,
  joinClasses
} from './lineageFlow'

const props = defineProps({
  graph: { type: Object, default: null },
  layout: { type: String, default: 'dagre' },
  focus: { type: String, default: '' },
  showIsolated: { type: Boolean, default: false }
})

const emit = defineEmits(['nodeClick', 'cycleDetected'])

const flowId = 'lineage-flow'
const flow = useVueFlow(flowId)

const isInitialized = ref(false)
const nodes = ref([])
const edges = ref([])

const nodeTypes = markRaw({ lineageNode: markRaw(LineageNode) })
const defaultEdgeOptions = {
  type: 'default',
  markerEnd: MarkerType.ArrowClosed,
  style: { stroke: '#A3B1BF', strokeWidth: 2 }
}

let adjacency = new Map()
let reverseAdjacency = new Map()
let edgeIdByPair = new Map()
let edgeBaseClassById = new Map()

const rebuildIndexes = () => {
  adjacency = new Map()
  reverseAdjacency = new Map()
  edgeIdByPair = new Map()
  edgeBaseClassById = new Map()

  for (const node of nodes.value) {
    adjacency.set(node.id, [])
    reverseAdjacency.set(node.id, [])
  }

  for (const edge of edges.value) {
    if (adjacency.has(edge.source)) adjacency.get(edge.source).push(edge.target)
    if (reverseAdjacency.has(edge.target)) reverseAdjacency.get(edge.target).push(edge.source)
    edgeIdByPair.set(`${edge.source}=>${edge.target}`, edge.id)
    edgeBaseClassById.set(edge.id, edge.class || '')
  }
}

const rebuildElements = async () => {
  const result = buildLineageFlow(props.graph, props.layout, { showIsolated: props.showIsolated })
  nodes.value = result.nodes
  edges.value = result.edges
  rebuildIndexes()

  if (result.cycles?.length) emit('cycleDetected', result.cycles)

  await nextTick()
  if (!isInitialized.value) return

  if (props.focus) {
    await focusNode(props.focus)
  } else {
    await fitGraph()
  }
}

watch(() => [props.graph, props.layout, props.showIsolated], rebuildElements, { immediate: true })

watch(
  () => props.focus,
  async (id) => {
    if (!id) return
    await nextTick()
    if (!isInitialized.value) return
    await focusNode(id)
  }
)

const handleInit = async () => {
  isInitialized.value = true
  await nextTick()

  if (props.focus) {
    await focusNode(props.focus)
  } else {
    await fitGraph()
  }
}

const setSelectedNode = (id) => {
  nodes.value = nodes.value.map((node) => ({ ...node, selected: node.id === id }))
}

const handleNodeClick = async ({ node }) => {
  setSelectedNode(node.id)
  emit('nodeClick', node.data)
  await focusNode(node.id)
}

const stripHighlightClasses = (className) =>
  String(className || '')
    .split(' ')
    .map((c) => c.trim())
    .filter((c) => c && c !== 'is-active' && c !== 'is-inactive')
    .join(' ')

const clearHighlight = () => {
  nodes.value = nodes.value.map((node) => ({ ...node, class: stripHighlightClasses(node.class) }))
  edges.value = edges.value.map((edge) => ({ ...edge, class: edgeBaseClassById.get(edge.id) || '' }))
}

const handleNodeMouseEnter = ({ node }) => {
  highlightDependencies(node.id)
}

const highlightDependencies = (nodeId) => {
  if (!nodeId) return

  const activeNodes = new Set([nodeId])
  const activeEdges = new Set()

  const walk = (startId, map, edgeKeyFn) => {
    const queue = [startId]
    while (queue.length) {
      const current = queue.shift()
      const neighbors = map.get(current) || []
      for (const next of neighbors) {
        const edgeId = edgeIdByPair.get(edgeKeyFn(current, next))
        if (edgeId) activeEdges.add(edgeId)
        if (activeNodes.has(next)) continue
        activeNodes.add(next)
        queue.push(next)
      }
    }
  }

  walk(nodeId, reverseAdjacency, (cur, next) => `${next}=>${cur}`)
  walk(nodeId, adjacency, (cur, next) => `${cur}=>${next}`)

  nodes.value = nodes.value.map((node) => {
    const base = stripHighlightClasses(node.class)
    const highlight = activeNodes.has(node.id) ? 'is-active' : 'is-inactive'
    return { ...node, class: joinClasses(base, highlight) }
  })

  edges.value = edges.value.map((edge) => {
    const base = edgeBaseClassById.get(edge.id) || ''
    const highlight = activeEdges.has(edge.id) ? 'is-active' : 'is-inactive'
    return { ...edge, class: joinClasses(base, highlight) }
  })
}

const fitGraph = async () => {
  if (!nodes.value.length) return false
  return flow.fitView({ padding: '18%', includeHiddenNodes: true, duration: 250 })
}

const focusNode = async (id) => {
  if (!id) return false
  const normalized = String(id)
  const exact = flow.findNode(normalized)
  const fallback = exact
    || nodes.value.find((item) => String(item.id) === normalized || String(item.data?.name || '') === normalized)
  const node = fallback ? flow.findNode(String(fallback.id)) : null
  if (!node) return false

  const width = node.dimensions?.width || LINEAGE_NODE_WIDTH
  const height = node.dimensions?.height || LINEAGE_NODE_HEIGHT

  const centerX = node.position.x + width / 2
  const centerY = node.position.y + height / 2

  const currentZoom = flow.viewport.value?.zoom ?? 1
  return flow.setCenter(centerX, centerY, { duration: 350, zoom: Math.max(currentZoom, 1) })
}

const searchNode = async (keyword) => {
  const normalized = String(keyword || '').trim()
  if (!normalized) return false

  const match = nodes.value.find((n) =>
    String(n.data?.name || '').includes(normalized)
    || String(n.data?.dbName || '').includes(normalized)
    || String(n.id || '').includes(normalized))
  if (!match) return false

  clearHighlight()
  setSelectedNode(match.id)
  await focusNode(match.id)
  highlightDependencies(match.id)
  return true
}

const miniMapNodeColor = (node) => getLayerColor(node?.data?.layer)

defineExpose({ focusNode, searchNode, fitGraph })
</script>

<style scoped>
.lineage-flow {
  width: 100%;
  height: 100%;
}

.lineage-flow :deep(.vue-flow__node) {
  border-radius: 12px;
}

.lineage-flow :deep(.vue-flow__node.is-isolated) {
  opacity: 0.9;
}

.lineage-flow :deep(.vue-flow__node.is-inactive) {
  opacity: 0.18;
}

.lineage-flow :deep(.vue-flow__edge.is-inactive) {
  opacity: 0.08;
}

.lineage-flow :deep(.vue-flow__node.is-active),
.lineage-flow :deep(.vue-flow__edge.is-active) {
  opacity: 1;
}

.lineage-flow :deep(.vue-flow__edge-path) {
  stroke-linecap: round;
}
</style>
