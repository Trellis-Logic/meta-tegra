// Auto-detect versions from GitHub Pages root
window.addEventListener('DOMContentLoaded', () => {
    const baseURL = '/meta-tegra/'; // change if your repo name is different
    const nav = document.querySelector('nav.book-header') || document.body;

    const container = document.createElement('div');
    container.style.cssText = 'margin-left:1rem; display:inline-block;';

    const select = document.createElement('select');
    select.style.padding = '0.3rem 0.5rem';
    select.style.fontSize = '0.9rem';
    select.style.borderRadius = '5px';

    // List of versions: fetch index.json from gh-pages root
    fetch(baseURL + 'versions.json')
        .then(resp => resp.json())
        .then(versions => {
            versions.forEach(v => {
                const opt = document.createElement('option');
                opt.value = baseURL + v.folder + '/';
                opt.text = v.name || v.folder;
                select.appendChild(opt);
            });

            // Set current version as selected
            const currentPath = window.location.pathname;
            const currentVersion = versions.find(v => currentPath.includes(v.folder));
            if (currentVersion) select.value = baseURL + currentVersion.folder + '/';
        })
        .catch(err => console.warn('Failed to load versions.json', err));

    select.addEventListener('change', e => {
        window.location.href = e.target.value;
    });

    container.appendChild(select);
    if (nav) nav.appendChild(container);
});

