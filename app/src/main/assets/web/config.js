window.APP_CONFIG = {
  // Smooth scrolling when the focus moves (off: lighter on TV boxes).
  smoothScroll: false,

  // Library routes on music.apple.com.
  libraryRoutes: { songs: '/library/songs', albums: '/library/albums', artists: '/library/artists' },

  // Home shelves pinned to the top, matched by section title (site language may vary).
  pinShelves: [
    /made for you|для вас|создано для вас/i,
    /heavy rotation|часто|в ротации/i
  ]
};
