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
    }
}
