import request from '@/utils/request'

export const settingsApi = {
    // Get DolphinScheduler config
    getDolphinConfig() {
        return request({
            url: '/v1/settings/dolphin',
            method: 'get'
        })
    },

    // Update DolphinScheduler config
    updateDolphinConfig(data) {
        return request({
            url: '/v1/settings/dolphin',
            method: 'put',
            data
        })
    },

    // Test DolphinScheduler connection
    testDolphinConnection(data) {
        return request({
            url: '/v1/settings/dolphin/test',
            method: 'post',
            data
        })
    },

    listMinioConfigs(params = {}) {
        return request({
            url: '/v1/settings/minio',
            method: 'get',
            params
        })
    },

    getMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'get'
        })
    },

    createMinioConfig(data) {
        return request({
            url: '/v1/settings/minio',
            method: 'post',
            data
        })
    },

    updateMinioConfig(id, data) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'put',
            data
        })
    },

    deleteMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}`,
            method: 'delete'
        })
    },

    setDefaultMinioConfig(id) {
        return request({
            url: `/v1/settings/minio/${id}/default`,
            method: 'post'
        })
    }
}
