import * as G6Import from '@antv/g6';
const G6 = G6Import.default || G6Import;

/**
 * LineageGraph handles the G6 graph visualization logic
 */
export class LineageGraph {
    constructor(container) {
        this.container = container;
        this.graph = null;
        this.data = null;

        // Register custom items
        this.registerCustomItems();
    }

    registerCustomItems() {
        // Custom Card Node
        G6.registerNode('card-node', {
            draw: (cfg, group) => {
                const { name, category, layer, inDegree, outDegree } = cfg;
                const width = 220;
                const height = 80;
                const r = 4;
                const color = cfg.style.stroke || '#409EFF'; // Border/Header color

                // Main box
                const shape = group.addShape('rect', {
                    attrs: {
                        x: -width / 2,
                        y: -height / 2,
                        width,
                        height,
                        radius: r,
                        fill: '#fff',
                        stroke: color,
                        lineWidth: 2,
                        shadowColor: '#eee',
                        shadowBlur: 10
                    },
                    name: 'main-box',
                    draggable: true,
                });

                // Header background
                group.addShape('rect', {
                    attrs: {
                        x: -width / 2,
                        y: -height / 2,
                        width,
                        height: 32,
                        radius: [r, r, 0, 0],
                        fill: color,
                        opacity: 0.1
                    },
                    name: 'header-box',
                    draggable: true,
                });

                // Header text (Table Name)
                group.addShape('text', {
                    attrs: {
                        text: name.length > 25 ? name.substr(0, 25) + '...' : name,
                        x: -width / 2 + 10,
                        y: -height / 2 + 20,
                        fontSize: 14,
                        fontWeight: 'bold',
                        fill: color,
                        cursor: 'pointer'
                    },
                    name: 'title-text',
                    draggable: true,
                });

                // Body Text: Layer
                group.addShape('text', {
                    attrs: {
                        text: `Layer: ${layer || category || 'N/A'}`,
                        x: -width / 2 + 10,
                        y: -height / 2 + 55,
                        fontSize: 12,
                        fill: '#666',
                    },
                    name: 'layer-text',
                    draggable: true,
                });

                // Body info: In/Out
                // We can add simple icons or text for In/Out degree if available
                if (inDegree !== undefined && outDegree !== undefined) {
                    group.addShape('text', {
                        attrs: {
                            text: `In: ${inDegree} / Out: ${outDegree}`,
                            x: width / 2 - 10,
                            y: -height / 2 + 55,
                            fontSize: 12,
                            fill: '#999',
                            textAlign: 'right'
                        },
                        name: 'degree-text',
                        draggable: true,
                    });
                }

                return shape;
            },
            // Define anchor points for edges
            getAnchorPoints() {
                return [
                    [0, 0.5], // Left
                    [1, 0.5], // Right
                    [0.5, 0], // Top
                    [0.5, 1], // Bottom
                ];
            }
        });
    }

    init(config = {}) {
        const width = this.container.scrollWidth;
        const height = this.container.scrollHeight || 500;

        this.graph = new G6.Graph({
            container: this.container,
            width,
            height,
            fitView: true,
            fitViewPadding: 20,
            modes: {
                default: ['drag-canvas', 'zoom-canvas', 'drag-node', 'activate-relations'],
            },
            defaultNode: {
                type: 'card-node',
            },
            defaultEdge: {
                type: 'cubic-horizontal',
                style: {
                    stroke: '#A3B1BF',
                    lineWidth: 2,
                    endArrow: true,
                },
            },
            nodeStateStyles: {
                active: {
                    opacity: 1,
                    shadowBlur: 15,
                    shadowColor: '#666'
                },
                inactive: {
                    opacity: 0.2,
                },
                highlight: {
                    stroke: '#ff0000',
                    lineWidth: 4
                }
            },
            edgeStateStyles: {
                active: {
                    stroke: '#409EFF',
                    lineWidth: 3,
                    opacity: 1
                },
                inactive: {
                    opacity: 0.1,
                }
            },
            layout: {
                type: 'dagre',
                rankdir: 'LR', // Left to Right
                nodesep: 30,
                ranksep: 80,
            },
            ...config
        });

        // Tooltip & Hover
        this.graph.on('node:mouseenter', (evt) => {
            this.graph.setItemState(evt.item, 'hover', true);
        });

        this.graph.on('node:mouseleave', (evt) => {
            this.graph.setItemState(evt.item, 'hover', false);
        });

        // Click handler
        this.graph.on('node:click', (evt) => {
            const node = evt.item;
            const model = node.getModel();

            // Focus and highlight
            this.graph.getNodes().forEach(n => {
                this.graph.clearItemStates(n, 'selected');
            });
            this.graph.setItemState(node, 'selected', true);

            if (this.onNodeClick) {
                this.onNodeClick(model);
            }
        });

        // Resize handler
        if (typeof window !== 'undefined') {
            window.addEventListener('resize', this.handleResize.bind(this));
        }
    }

    render(data) {
        if (!this.graph) return;
        this.data = data;

        // Detect cycles
        const cycles = this.findCycles(data);
        if (cycles.length > 0) {
            console.warn('Detected cycles:', cycles);
            // Mark cycle edges
            const cycleEdges = new Set();
            cycles.forEach(cycle => {
                for (let i = 0; i < cycle.length; i++) {
                    const source = cycle[i];
                    const target = cycle[(i + 1) % cycle.length];
                    cycleEdges.add(`${source}->${target}`);
                }
            });

            data.edges.forEach(edge => {
                if (cycleEdges.has(`${edge.source}->${edge.target}`)) {
                    edge.style = { ...edge.style, stroke: '#F56C6C', lineWidth: 3, lineDash: [5, 5] };
                    edge.stateStyles = { ...edge.stateStyles, active: { stroke: '#F56C6C', lineWidth: 4 } };
                }
            });

            // Emit event or callback if needed, for now we just rely on visual cue + return value
            if (this.onCycleDetected) {
                this.onCycleDetected(cycles);
            }
        }

        this.graph.data(data);
        this.graph.render();
        return cycles.length > 0;
    }

    findCycles(data) {
        const adjacency = {};
        (data.nodes || []).forEach(n => adjacency[n.id] = []);
        (data.edges || []).forEach(e => {
            if (adjacency[e.source]) adjacency[e.source].push(e.target);
        });

        const visited = new Set();
        const recStack = new Set();
        const cycles = [];

        const dfs = (nodeId, path) => {
            visited.add(nodeId);
            recStack.add(nodeId);
            path.push(nodeId);

            const neighbors = adjacency[nodeId] || [];
            for (const neighbor of neighbors) {
                if (!visited.has(neighbor)) {
                    dfs(neighbor, path);
                } else if (recStack.has(neighbor)) {
                    // Cycle detected
                    const cycleStartIndex = path.indexOf(neighbor);
                    cycles.push(path.slice(cycleStartIndex));
                }
            }

            recStack.delete(nodeId);
            path.pop();
        };

        (data.nodes || []).forEach(node => {
            if (!visited.has(node.id)) {
                dfs(node.id, []);
            }
        });

        return cycles;
    }

    changeLayout(layoutType) {
        if (!this.graph) return;

        let layoutConfig = {};
        let edgeType = 'cubic-horizontal';

        if (layoutType === 'dagre') {
            layoutConfig = {
                type: 'dagre',
                rankdir: 'LR',
                nodesep: 30,
                ranksep: 80,
            };
        } else if (layoutType === 'force') {
            layoutConfig = {
                type: 'force',
                preventOverlap: true,
                nodeSize: 220,
                linkDistance: 150,
            };
        } else if (layoutType === 'indented') {
            // Use Dagre TB (Top-Bottom) to simulate a Tree view on G6.Graph
            // as 'indented' is only for TreeGraph.
            layoutConfig = {
                type: 'dagre',
                rankdir: 'TB',
                nodesep: 30,
                ranksep: 80,
            };
            edgeType = 'cubic-vertical';
        }

        // Update edges to match layout orientation
        this.graph.getEdges().forEach(edge => {
            this.graph.updateItem(edge, {
                type: edgeType
            });
        });

        this.graph.updateLayout(layoutConfig);
    }

    searchNode(keyword) {
        if (!this.graph) return;
        const nodes = this.graph.getNodes();
        let found = null;

        nodes.forEach(node => {
            const model = node.getModel();
            this.graph.clearItemStates(node, 'highlight');
            if (keyword && model.name.includes(keyword)) {
                this.graph.setItemState(node, 'highlight', true);
                if (!found) found = node;
            }
        });

        if (found) {
            this.graph.focusItem(found, true, {
                easing: 'easeCubic',
                duration: 500,
            });
        }
    }

    focusNode(id) {
        if (!this.graph || !id) return;
        const node = this.graph.findById(id);
        if (node) {
            this.graph.focusItem(node, true, {
                easing: 'easeCubic',
                duration: 500,
            });
            this.graph.setItemState(node, 'selected', true);
        }
    }

    handleResize() {
        if (this.graph && this.container) {
            this.graph.changeSize(this.container.scrollWidth, this.container.scrollHeight);
        }
    }

    destroy() {
        if (this.graph) {
            this.graph.destroy();
        }
        if (typeof window !== 'undefined') {
            window.removeEventListener('resize', this.handleResize.bind(this));
        }
    }
}
