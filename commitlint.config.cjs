// Single-line Conventional Commits: a header and nothing else.
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'body-empty': [2, 'always'],
    'footer-empty': [2, 'always'],
  },
};
