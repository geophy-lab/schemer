import Loading from './loading.vue'
const component = import(/* webpackChunkName:"schema-list" */'./component.vue')
export default () => ({
  component,
  loading: Loading,
  delay: 5000,
})
