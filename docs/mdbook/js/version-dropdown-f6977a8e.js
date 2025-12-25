(function () {
    async function addVersionDropdown(menuBar) {
        if (document.querySelector('#mdbook-version-select')) return;

        // Fetch versions.json
        let versions = [];
        try {
            const res = await fetch('/meta-tegra/versions.json');
            if (res.ok) {
                versions = await res.json();
            } else {
                console.warn('Could not fetch versions.json:', res.status);
            }
        } catch (err) {
            console.error('Error fetching versions.json:', err);
            return;
        }

        if (!versions.length) return;

        // ---- Container (material-style) ----
        const container = document.createElement('div');
        container.id = 'mdbook-version-container';
        container.style.cssText = `
            display: flex;
            align-items: center;
            margin-right: 0.75rem;
        `;

        // Optional label (matches material subtle text)
        const label = document.createElement('span');
        label.textContent = 'Version';
        label.style.cssText = `
            font-size: 0.85rem;
            opacity: 0.85;
            margin-right: 0.4rem;
            white-space: nowrap;
        `;
        container.appendChild(label);

        // ---- Select (material-style) ----
        const select = document.createElement('select');
        select.id = 'mdbook-version-select';
        select.style.cssText = `
            height: 2rem;
            padding: 0 0.5rem;
            font-size: 0.9rem;
            border-radius: 4px;
            border: 1px solid var(--theme-border-color);
            background-color: var(--theme-popup-bg);
            color: var(--theme-text-color);
            font-weight: 500;
            cursor: pointer;
            outline: none;
        `;

        // Hover / focus behavior like material
        select.addEventListener('focus', () => {
            select.style.borderColor = 'var(--theme-accent-color)';
        });
        select.addEventListener('blur', () => {
            select.style.borderColor = 'var(--theme-border-color)';
        });

        // Populate options
        versions.forEach(v => {
            const opt = document.createElement('option');
            opt.value = v.folder.endsWith('/') ? v.folder : v.folder + '/';
            opt.text = v.name;
            select.appendChild(opt);
        });

        // Select current version based on URL
        const path = window.location.pathname;
        for (let i = 0; i < select.options.length; i++) {
            if (path.includes(select.options[i].value)) {
                select.selectedIndex = i;
                break;
            }
        }

        // Navigate on change
        select.addEventListener('change', e => {
            const selectedBranch = e.target.value.replace(/\/$/, '');

            // Current URL: /meta-tegra/master/page
            const parts = window.location.pathname.split('/');
            const repo = parts[1] || '';
            const pagePath = parts.slice(3).join('/');

            const newUrl = `/${repo}/${selectedBranch}/${pagePath}`;
            window.location.href = newUrl;
        });

        container.appendChild(select);

        // ---- Insert on LEFT side of menu bar ----
        const firstControl = menuBar.querySelector('button, a');
        menuBar.insertBefore(container, firstControl || menuBar.firstChild);
    }

    // Wait until the menu bar exists
    const observer = new MutationObserver((mutations, obs) => {
        const menuBar = document.getElementById('mdbook-menu-bar');
        if (menuBar) {
            addVersionDropdown(menuBar);
            obs.disconnect();
        }
    });

    observer.observe(document.body, { childList: true, subtree: true });
})();
