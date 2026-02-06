import { MarkerType } from '@vue-flow/core'

export const LINEAGE_NODE_WIDTH = 240
export const LINEAGE_NODE_HEIGHT = 88

const LAYER_COLORS = {
  ODS: '#409EFF',
  DWD: '#67C23A',
  DIM: '#E6A23C',
  DWS: '#F56C6C',
  ADS: '#909399'
}

export function getLayerColor(layer) {
  if (!layer) return LAYER_COLORS.ODS
  const normalized = String(layer).toUpperCase()
  return LAYER_COLORS[normalized] || LAYER_COLORS.ODS
}

function dedupeEdges(rawEdges, validNodeIds) {
  const edgeMap = new Map()

  for (const edge of rawEdges) {
    const source = edge?.source ? String(edge.source) : ''
    const target = edge?.target ? String(edge.target) : ''
    if (!source || !target) continue
    if (!validNodeIds.has(source) || !validNodeIds.has(target)) continue

    const key = `${source}=>${target}`
    const existing = edgeMap.get(key)
    if (existing) {
      if (edge?.taskId != null) existing.taskIds.add(edge.taskId)
      continue
    }

    edgeMap.set(key, {
      source,
      target,
      taskIds: new Set(edge?.taskId != null ? [edge.taskId] : [])
    })
  }

  return Array.from(edgeMap.values())
}

export function buildLineageFlow(graph, layoutType = 'dagre', options = {}) {
  const rawNodes = Array.isArray(graph?.nodes) ? graph.nodes : []
  const rawEdges = Array.isArray(graph?.edges) ? graph.edges : []
  const showIsolated = options?.showIsolated === true

  const nodesById = new Map()
  for (const node of rawNodes) {
    const id = node?.id ? String(node.id) : ''
    if (!id) continue
    nodesById.set(id, node)
  }

  const nodeIds = new Set(nodesById.keys())
  const dedupedEdges = dedupeEdges(rawEdges, nodeIds)

  const baseEdges = dedupedEdges.map(({ source, target, taskIds }) => ({
    id: `e:${source}→${target}`,
    source,
    target,
    type: 'default',
    markerEnd: MarkerType.ArrowClosed,
    style: {
      stroke: '#A3B1BF',
      strokeWidth: 2
    },
    data: { taskIds: Array.from(taskIds) }
  }))

  const inDegree = new Map()
  const outDegree = new Map()
  for (const edge of baseEdges) {
    inDegree.set(edge.target, (inDegree.get(edge.target) || 0) + 1)
    outDegree.set(edge.source, (outDegree.get(edge.source) || 0) + 1)
  }

  const rankdir = layoutType === 'indented' ? 'TB' : 'LR'

  const allNodes = Array.from(nodesById.entries()).map(([id, raw]) => {
    const nodeIn = inDegree.get(id) || 0
    const nodeOut = outDegree.get(id) || 0
    const isIsolated = nodeIn === 0 && nodeOut === 0
    return {
      id,
      type: 'lineageNode',
      position: { x: 0, y: 0 },
      draggable: false,
      class: isIsolated ? 'is-isolated' : '',
      data: {
        rankdir,
        tableId: raw?.tableId,
        clusterId: raw?.clusterId,
        dbName: raw?.dbName,
        id,
        name: raw?.name || id,
        layer: raw?.layer,
        comment: raw?.comment,
        businessDomain: raw?.businessDomain,
        dataDomain: raw?.dataDomain,
        inDegree: nodeIn,
        outDegree: nodeOut,
        isIsolated
      }
    }
  })

  const connectedNodes = allNodes.filter((node) => !node.data?.isIsolated)
  const isolatedNodes = allNodes.filter((node) => !!node.data?.isIsolated)

  if (connectedNodes.length > 0) {
    applyLayeredLayout(connectedNodes, baseEdges, rankdir)
  }
  if (showIsolated && isolatedNodes.length > 0) {
    placeIsolatedNodes(connectedNodes, isolatedNodes, rankdir)
  }

  const visibleNodes = showIsolated ? [...connectedNodes, ...isolatedNodes] : connectedNodes
  const visibleNodeIds = new Set(visibleNodes.map((node) => node.id))
  const visibleEdges = baseEdges.filter((edge) => visibleNodeIds.has(edge.source) && visibleNodeIds.has(edge.target))

  const cycles = findCycles(visibleNodes, visibleEdges)
  const cycleEdgeKeys = new Set()
  for (const cycle of cycles) {
    if (cycle.length < 2) continue
    for (let i = 0; i < cycle.length; i++) {
      const source = cycle[i]
      const target = cycle[(i + 1) % cycle.length]
      cycleEdgeKeys.add(`${source}=>${target}`)
    }
  }

  for (const edge of visibleEdges) {
    if (!cycleEdgeKeys.has(`${edge.source}=>${edge.target}`)) continue
    edge.class = joinClasses(edge.class, 'is-cycle')
    edge.style = {
      ...edge.style,
      stroke: '#F56C6C',
      strokeWidth: 3,
      strokeDasharray: '5,5'
    }
  }

  return { nodes: visibleNodes, edges: visibleEdges, cycles, rankdir }
}

function placeIsolatedNodes(anchorNodes, isolatedNodes, rankdir) {
  if (!Array.isArray(isolatedNodes) || isolatedNodes.length === 0) {
    return
  }

  isolatedNodes.sort((a, b) => {
    const aName = String(a?.data?.name || a?.id || '')
    const bName = String(b?.data?.name || b?.id || '')
    return aName.localeCompare(bName)
  })

  const nodeSep = 18
  const rowSep = 18
  const laneGap = 140
  const maxPerRow = rankdir === 'TB' ? 4 : 5

  const firstRowCount = Math.min(maxPerRow, isolatedNodes.length)
  const rowWidth = firstRowCount * LINEAGE_NODE_WIDTH + Math.max(0, firstRowCount - 1) * nodeSep

  let baseX = -rowWidth / 2
  let baseY = 0
  if (anchorNodes.length > 0) {
    const minX = Math.min(...anchorNodes.map((node) => node.position.x))
    const maxX = Math.max(...anchorNodes.map((node) => node.position.x))
    const maxY = Math.max(...anchorNodes.map((node) => node.position.y))
    const graphWidth = maxX - minX + LINEAGE_NODE_WIDTH
    baseX = minX + (graphWidth - rowWidth) / 2
    baseY = maxY + LINEAGE_NODE_HEIGHT + laneGap
  }

  for (let i = 0; i < isolatedNodes.length; i++) {
    const row = Math.floor(i / maxPerRow)
    const col = i % maxPerRow
    isolatedNodes[i].position = {
      x: baseX + col * (LINEAGE_NODE_WIDTH + nodeSep),
      y: baseY + row * (LINEAGE_NODE_HEIGHT + rowSep)
    }
  }
}

function applyLayeredLayout(nodes, edges, rankdir) {
  const nodeIds = nodes.map((n) => n.id)
  const nodeById = new Map(nodes.map((n) => [n.id, n]))

  const adjacency = new Map(nodeIds.map((id) => [id, []]))
  const reverseAdjacency = new Map(nodeIds.map((id) => [id, []]))
  for (const edge of edges) {
    if (!adjacency.has(edge.source) || !reverseAdjacency.has(edge.target)) continue
    adjacency.get(edge.source).push(edge.target)
    reverseAdjacency.get(edge.target).push(edge.source)
  }

  const sccs = stronglyConnectedComponents(nodeIds, adjacency)
  const sccByNode = new Map()
  for (let sccId = 0; sccId < sccs.length; sccId++) {
    for (const id of sccs[sccId]) sccByNode.set(id, sccId)
  }

  const sccAdjacency = new Map()
  const sccIndegree = new Map()
  for (let sccId = 0; sccId < sccs.length; sccId++) {
    sccAdjacency.set(sccId, new Set())
    sccIndegree.set(sccId, 0)
  }

  for (const edge of edges) {
    const from = sccByNode.get(edge.source)
    const to = sccByNode.get(edge.target)
    if (from == null || to == null || from === to) continue

    const targets = sccAdjacency.get(from)
    if (targets.has(to)) continue
    targets.add(to)
    sccIndegree.set(to, (sccIndegree.get(to) || 0) + 1)
  }

  const sccRank = new Map()
  const queue = []
  for (const [sccId, indegree] of sccIndegree.entries()) {
    if (indegree === 0) {
      queue.push(sccId)
      sccRank.set(sccId, 0)
    }
  }

  while (queue.length) {
    const current = queue.shift()
    const currentRank = sccRank.get(current) || 0
    const targets = sccAdjacency.get(current) || new Set()
    for (const target of targets) {
      sccRank.set(target, Math.max(sccRank.get(target) || 0, currentRank + 1))
      sccIndegree.set(target, (sccIndegree.get(target) || 0) - 1)
      if (sccIndegree.get(target) === 0) queue.push(target)
    }
  }

  const rankByNode = new Map()
  let maxRank = 0
  for (const id of nodeIds) {
    const rank = sccRank.get(sccByNode.get(id)) || 0
    rankByNode.set(id, rank)
    maxRank = Math.max(maxRank, rank)
  }

  const layers = new Map()
  for (const id of nodeIds) {
    const rank = rankByNode.get(id) || 0
    if (!layers.has(rank)) layers.set(rank, [])
    layers.get(rank).push(id)
  }

  for (const [rank, ids] of layers.entries()) {
    ids.sort((a, b) => {
      const aName = String(nodeById.get(a)?.data?.name || a)
      const bName = String(nodeById.get(b)?.data?.name || b)
      return aName.localeCompare(bName)
    })
    layers.set(rank, ids)
  }

  const orderById = new Map()
  const syncOrder = () => {
    for (const [rank, ids] of layers.entries()) {
      for (let i = 0; i < ids.length; i++) orderById.set(ids[i], i)
    }
  }
  syncOrder()

  const sweepCount = 4
  for (let iter = 0; iter < sweepCount; iter++) {
    // Forward sweep: order by predecessors
    for (let rank = 1; rank <= maxRank; rank++) {
      const ids = layers.get(rank) || []
      if (!ids.length) continue

      const scored = ids.map((id) => {
        const predecessors = reverseAdjacency.get(id) || []
        const neighborOrders = predecessors
          .filter((p) => (rankByNode.get(p) ?? 0) < rank)
          .map((p) => orderById.get(p))
          .filter((o) => typeof o === 'number')

        const avg =
          neighborOrders.length > 0
            ? neighborOrders.reduce((sum, o) => sum + o, 0) / neighborOrders.length
            : orderById.get(id) || 0

        return { id, avg, fallback: orderById.get(id) || 0 }
      })

      scored.sort((a, b) => a.avg - b.avg || a.fallback - b.fallback || a.id.localeCompare(b.id))
      layers.set(rank, scored.map((s) => s.id))
      syncOrder()
    }

    // Backward sweep: order by successors
    for (let rank = maxRank - 1; rank >= 0; rank--) {
      const ids = layers.get(rank) || []
      if (!ids.length) continue

      const scored = ids.map((id) => {
        const successors = adjacency.get(id) || []
        const neighborOrders = successors
          .filter((s) => (rankByNode.get(s) ?? 0) > rank)
          .map((s) => orderById.get(s))
          .filter((o) => typeof o === 'number')

        const avg =
          neighborOrders.length > 0
            ? neighborOrders.reduce((sum, o) => sum + o, 0) / neighborOrders.length
            : orderById.get(id) || 0

        return { id, avg, fallback: orderById.get(id) || 0 }
      })

      scored.sort((a, b) => a.avg - b.avg || a.fallback - b.fallback || a.id.localeCompare(b.id))
      layers.set(rank, scored.map((s) => s.id))
      syncOrder()
    }
  }

  const nodeSep = 26
  const rankSep = 120

  if (rankdir === 'TB') {
    for (let rank = 0; rank <= maxRank; rank++) {
      const ids = layers.get(rank) || []
      const totalWidth = ids.length * LINEAGE_NODE_WIDTH + Math.max(0, ids.length - 1) * nodeSep
      const startX = -totalWidth / 2
      const y = rank * (LINEAGE_NODE_HEIGHT + rankSep)
      for (let i = 0; i < ids.length; i++) {
        const id = ids[i]
        const node = nodeById.get(id)
        if (!node) continue
        node.position = {
          x: startX + i * (LINEAGE_NODE_WIDTH + nodeSep),
          y
        }
      }
    }
    return
  }

  // LR
  for (let rank = 0; rank <= maxRank; rank++) {
    const ids = layers.get(rank) || []
    const totalHeight = ids.length * LINEAGE_NODE_HEIGHT + Math.max(0, ids.length - 1) * nodeSep
    const startY = -totalHeight / 2
    const x = rank * (LINEAGE_NODE_WIDTH + rankSep)
    for (let i = 0; i < ids.length; i++) {
      const id = ids[i]
      const node = nodeById.get(id)
      if (!node) continue
      node.position = {
        x,
        y: startY + i * (LINEAGE_NODE_HEIGHT + nodeSep)
      }
    }
  }
}

function stronglyConnectedComponents(nodeIds, adjacency) {
  let index = 0
  const indices = new Map()
  const lowlink = new Map()
  const stack = []
  const onStack = new Set()
  const components = []

  const strongConnect = (id) => {
    indices.set(id, index)
    lowlink.set(id, index)
    index++

    stack.push(id)
    onStack.add(id)

    const neighbors = adjacency.get(id) || []
    for (const neighbor of neighbors) {
      if (!indices.has(neighbor)) {
        strongConnect(neighbor)
        lowlink.set(id, Math.min(lowlink.get(id), lowlink.get(neighbor)))
      } else if (onStack.has(neighbor)) {
        lowlink.set(id, Math.min(lowlink.get(id), indices.get(neighbor)))
      }
    }

    if (lowlink.get(id) !== indices.get(id)) return

    const component = []
    while (stack.length) {
      const popped = stack.pop()
      onStack.delete(popped)
      component.push(popped)
      if (popped === id) break
    }
    components.push(component)
  }

  for (const id of nodeIds) {
    if (!indices.has(id)) strongConnect(id)
  }

  return components
}

function normalizeCycle(cycle) {
  if (!cycle.length) return ''
  const minId = cycle.reduce((min, cur) => (cur < min ? cur : min), cycle[0])
  const startIndex = cycle.indexOf(minId)
  const rotated = [...cycle.slice(startIndex), ...cycle.slice(0, startIndex)]
  return rotated.join('→')
}

function findCycles(nodes, edges) {
  const adjacency = new Map()
  for (const node of nodes) adjacency.set(node.id, [])
  for (const edge of edges) {
    const list = adjacency.get(edge.source)
    if (list) list.push(edge.target)
  }

  const visited = new Set()
  const stack = new Set()
  const path = []
  const cycles = []
  const cycleKeySet = new Set()

  const dfs = (id) => {
    visited.add(id)
    stack.add(id)
    path.push(id)

    const neighbors = adjacency.get(id) || []
    for (const neighbor of neighbors) {
      if (!visited.has(neighbor)) {
        dfs(neighbor)
        continue
      }

      if (!stack.has(neighbor)) continue
      const startIndex = path.indexOf(neighbor)
      if (startIndex === -1) continue
      const cycle = path.slice(startIndex)
      if (cycle.length < 2) continue

      const key = normalizeCycle(cycle)
      if (cycleKeySet.has(key)) continue
      cycleKeySet.add(key)
      cycles.push(cycle)
    }

    stack.delete(id)
    path.pop()
  }

  for (const id of adjacency.keys()) {
    if (!visited.has(id)) dfs(id)
  }

  return cycles
}

export function joinClasses(...classes) {
  return classes
    .flatMap((c) => String(c || '').split(' '))
    .map((c) => c.trim())
    .filter(Boolean)
    .join(' ')
}
